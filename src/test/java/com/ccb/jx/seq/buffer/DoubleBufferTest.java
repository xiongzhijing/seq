
package com.ccb.jx.seq.buffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DoubleBuffer} 的单元测试。
 * <p>
 * 测试双缓冲机制的正常分配、Buffer 切换、备用加载、
 * 并发 loadStandby 安全性（S2）等核心行为。
 * </p>
 *
 * @author XZJ
 */
@DisplayName("DoubleBuffer 双缓冲测试")
class DoubleBufferTest {

    /**
     * 默认的号段提供者：每次返回一个 [start, start+99] 的 100 值号段。
     */
    private final AtomicLong counter = new AtomicLong(1);
    private final Supplier<Segment> defaultSupplier = () -> {
        long start = counter.getAndAdd(100);
        return new Segment(start, start + 99);
    };

    @Test
    @DisplayName("应从当前 Segment 依次分配值")
    void shouldReturnValuesFromCurrentSegment() {
        DoubleBuffer buffer = new DoubleBuffer("test", defaultSupplier);

        assertEquals(1L, buffer.next());
        assertEquals(2L, buffer.next());
        assertEquals(3L, buffer.next());
    }

    @Test
    @DisplayName("当前 Segment 耗尽后应切换到备用 Segment")
    void shouldSwitchBufferWhenCurrentExhausted() {
        // 使用每段仅 3 个值的小号段，快速触发切换
        AtomicLong smallCounter = new AtomicLong(1);
        Supplier<Segment> smallSupplier = () -> {
            long start = smallCounter.getAndAdd(3);
            return new Segment(start, start + 2);
        };

        DoubleBuffer buffer = new DoubleBuffer("test", smallSupplier);

        // 消耗第一段 (1,2,3)
        assertEquals(1L, buffer.next());
        assertEquals(2L, buffer.next());
        assertEquals(3L, buffer.next());

        // 第 4 次调用应触发切换，第二段起始为 4
        assertEquals(4L, buffer.next());
        assertEquals(5L, buffer.next());
        assertEquals(6L, buffer.next());

        // 再切换一次
        assertEquals(7L, buffer.next());
    }

    @Test
    @DisplayName("loadStandby 应加载备用 Segment")
    void shouldLoadStandbySegment() {
        // 先触发延迟初始化，消费第一个号段 [1, 100]
        AtomicLong localCounter = new AtomicLong(1);
        Supplier<Segment> supplier = () -> {
            long start = localCounter.getAndAdd(100);
            return new Segment(start, start + 99);
        };
        DoubleBuffer buffer = new DoubleBuffer("test", supplier);

        assertTrue(buffer.getStandby() instanceof EmptySegment);
        buffer.next(); // 触发延迟初始化，current = [1, 100]
        buffer.loadStandby(); // standby = [101, 200]
        assertNotNull(buffer.getStandby());
        assertFalse(buffer.getStandby() instanceof EmptySegment);

        Segment standby = buffer.getStandby();
        assertEquals(101L, standby.getStart());
        assertEquals(200L, standby.getEnd());
    }

    @Test
    @DisplayName("重复调用 loadStandby 不应重复加载")
    void shouldNotLoadStandbyTwice() {
        DoubleBuffer buffer = new DoubleBuffer("test", defaultSupplier);

        buffer.loadStandby();
        Segment firstStandby = buffer.getStandby();

        // 再次调用不应替换已加载的备用段
        buffer.loadStandby();
        assertSame(firstStandby, buffer.getStandby());
    }

    @Test
    @DisplayName("S2: 多线程并发调用 loadStandby 不应重复加载")
    void shouldNotLoadStandbyConcurrently() throws Exception {
        AtomicInteger loadCount = new AtomicInteger(0);
        AtomicLong concurrentCounter = new AtomicLong(1);
        Supplier<Segment> trackingSupplier = () -> {
            loadCount.incrementAndGet();
            long start = concurrentCounter.getAndAdd(100);
            return new Segment(start, start + 99);
        };

        DoubleBuffer buffer = new DoubleBuffer("test", trackingSupplier);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    buffer.loadStandby();
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

        // 延迟初始化：构造函数不再同步加载，loadStandby 本身仅触发 1 次加载
        assertEquals(1, loadCount.get(), "loadStandby should only load once despite concurrent calls");
        assertNotNull(buffer.getStandby());
        assertFalse(buffer.getStandby() instanceof EmptySegment);
    }

    @Test
    @DisplayName("remaining() 应返回当前段剩余数量（无备用）")
    void shouldCalculateCurrentRemaining() {
        DoubleBuffer buffer = new DoubleBuffer("test", defaultSupplier);

        buffer.next(); // 触发延迟初始化，current = [1, 100]，同时消耗第一个值
        assertEquals(99L, buffer.remaining());

        buffer.next();
        assertEquals(98L, buffer.remaining());
    }

    @Test
    @DisplayName("remaining() 应累计当前段和备用段的总剩余")
    void shouldSumStandbyInRemaining() {
        DoubleBuffer buffer = new DoubleBuffer("test", defaultSupplier);

        buffer.next(); // 触发延迟初始化，current = [1, 100]，消耗 1 个值
        buffer.loadStandby();
        // 当前段剩余 99 + 备用段剩余 100
        assertEquals(199L, buffer.remaining());

        buffer.next();
        // 当前段剩余 98 + 备用段剩余 100
        assertEquals(198L, buffer.remaining());
    }

    @Test
    @DisplayName("getCurrent 应返回当前 Segment")
    void shouldReturnCurrentSegment() {
        DoubleBuffer buffer = new DoubleBuffer("test", defaultSupplier);

        buffer.next(); // 触发延迟初始化
        Segment current = buffer.getCurrent();
        assertNotNull(current);
        assertFalse(current instanceof EmptySegment);
        assertEquals(1L, current.getStart());
        assertEquals(100L, current.getEnd());
    }

    @Test
    @DisplayName("异步提交器设置后应正常工作")
    void shouldWorkWithAsyncSubmitter() {
        DoubleBuffer buffer = new DoubleBuffer("test", defaultSupplier);

        // 设置一个同步执行的"异步"提交器（测试中简化）
        buffer.setAsyncTaskSubmitter(() -> buffer.loadStandby());

        // 消耗约 90% 的号段（90 个值），应触发预加载
        for (int i = 0; i < 90; i++) {
            buffer.next();
        }

        // 预加载应已触发，备用段非空
        assertNotNull(buffer.getStandby());
        assertFalse(buffer.getStandby() instanceof EmptySegment);
    }

    @Test
    @DisplayName("耗尽后未设异步提交器应同步加载备用")
    void shouldSyncLoadWhenNoAsyncSubmitter() {
        // 小号段
        AtomicLong smallCounter = new AtomicLong(1);
        Supplier<Segment> smallSupplier = () -> {
            long start = smallCounter.getAndAdd(3);
            return new Segment(start, start + 2);
        };

        DoubleBuffer buffer = new DoubleBuffer("test", smallSupplier);
        // 不设置异步提交器，预加载会降级为同步

        // 消耗所有值（共 6 个值，经历两次切换）
        for (int i = 0; i < 6; i++) {
            assertTrue(buffer.next() >= 0, "Should keep returning valid values");
        }

        // 第 7 次获取应再次切换
        assertEquals(7L, buffer.next());
    }

    @Test
    @DisplayName("初始状态 current 和 standby 应为 EmptySegment")
    void shouldHaveEmptySegmentInitially() {
        DoubleBuffer buffer = new DoubleBuffer("test", defaultSupplier);

        assertTrue(buffer.getCurrent() instanceof EmptySegment);
        assertTrue(buffer.getStandby() instanceof EmptySegment);
        assertEquals(0L, buffer.remaining());
    }
}
