
package com.ccb.jx.seq.buffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Segment} 的单元测试。
 * <p>
 * 测试号段模型的正常分配、耗尽、剩余容量和使用率等核心行为。
 * </p>
 *
 * @author XZJ
 */
@DisplayName("Segment 号段模型测试")
class SegmentTest {

    @Test
    @DisplayName("正常分配：应按起始值递增返回")
    void shouldReturnSequentialValues() {
        Segment segment = new Segment(1, 5);

        assertEquals(1L, segment.next());
        assertEquals(2L, segment.next());
        assertEquals(3L, segment.next());
        assertEquals(4L, segment.next());
        assertEquals(5L, segment.next());
    }

    @Test
    @DisplayName("耗尽后返回 -1")
    void shouldReturnMinusOneWhenExhausted() {
        Segment segment = new Segment(1, 3);

        assertEquals(1L, segment.next());
        assertEquals(2L, segment.next());
        assertEquals(3L, segment.next());
        // 耗尽
        assertEquals(-1L, segment.next());
        // 继续调用仍返回 -1
        assertEquals(-1L, segment.next());
    }

    @Test
    @DisplayName("大起始值号段应正确分配")
    void shouldWorkWithLargeStartValue() {
        Segment segment = new Segment(100, 102);

        assertEquals(100L, segment.next());
        assertEquals(101L, segment.next());
        assertEquals(102L, segment.next());
        assertEquals(-1L, segment.next());
    }

    @Test
    @DisplayName("remaining() 应返回正确剩余数量")
    void shouldCalculateRemainingCorrectly() {
        Segment segment = new Segment(1, 10);

        assertEquals(10L, segment.remaining());

        segment.next();
        assertEquals(9L, segment.remaining());

        // 消耗到耗尽
        while (segment.next() != -1) {
            // consume
        }
        assertEquals(0L, segment.remaining());

        // 耗尽后保持 0
        assertEquals(0L, segment.remaining());
    }

    @Test
    @DisplayName("isExhausted() 应正确反映耗尽状态")
    void shouldReportExhaustedCorrectly() {
        Segment segment = new Segment(1, 3);

        assertFalse(segment.isExhausted());

        segment.next();
        assertFalse(segment.isExhausted());

        segment.next();
        assertFalse(segment.isExhausted());

        segment.next();
        // 此时 current=4 > end=3
        assertTrue(segment.isExhausted());
    }

    @Test
    @DisplayName("usage() 应从 0.0 到 1.0 递增")
    void shouldCalculateUsageCorrectly() {
        Segment segment = new Segment(1, 10);

        assertEquals(0.0, segment.usage(), 0.001);

        segment.next(); // 使用 1 个
        assertEquals(0.1, segment.usage(), 0.001);

        segment.next(); // 使用 2 个
        assertEquals(0.2, segment.usage(), 0.001);

        // 消耗到耗尽
        while (segment.next() != -1) {
            // consume
        }
        assertEquals(1.0, segment.usage(), 0.001);
    }

    @Test
    @DisplayName("usage() 不应超过 1.0")
    void usageShouldNotExceedOne() {
        Segment segment = new Segment(1, 1);

        assertEquals(0.0, segment.usage(), 0.001);

        segment.next(); // 耗尽
        assertEquals(1.0, segment.usage(), 0.001);

        // 超额调用 next() 后 usage 仍为 1.0
        segment.next();
        assertEquals(1.0, segment.usage(), 0.001);
    }

    @Test
    @DisplayName("start > end 时应抛出 IllegalArgumentException")
    void shouldThrowOnInvalidRange() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Segment(10, 1));
        assertTrue(ex.getMessage().contains("start"));
        assertTrue(ex.getMessage().contains("end"));
    }

    @Test
    @DisplayName("start == end 应允许（单值号段）")
    void shouldAllowSingleValueSegment() {
        Segment segment = new Segment(5, 5);

        assertEquals(5L, segment.next());
        assertEquals(-1L, segment.next());
        assertTrue(segment.isExhausted());
    }

    @Test
    @DisplayName("getter 应返回构造时传入的值")
    void shouldProvideAccessors() {
        Segment segment = new Segment(5, 15);

        assertEquals(5L, segment.getStart());
        assertEquals(15L, segment.getEnd());
        assertTrue(segment.isInitialized());
        assertTrue(segment.getLoadTime() > 0);
    }

    @Test
    @DisplayName("used() 应返回已使用的数量")
    void shouldCalculateUsed() {
        Segment segment = new Segment(10, 20);

        assertEquals(0L, segment.used());

        segment.next();
        assertEquals(1L, segment.used());

        segment.next();
        assertEquals(2L, segment.used());
    }

    @Test
    @DisplayName("toString() 应包含关键信息")
    void toStringShouldContainKeyInfo() {
        Segment segment = new Segment(1, 10);
        String str = segment.toString();

        assertTrue(str.contains("start=1"));
        assertTrue(str.contains("end=10"));
        assertTrue(str.contains("remaining"));
    }

    // ==================== EmptySegment 测试 ====================

    @Test
    @DisplayName("EmptySegment.INSTANCE 应为单例")
    void emptySegmentShouldBeSingleton() {
        assertSame(EmptySegment.INSTANCE, EmptySegment.INSTANCE);
    }

    @Test
    @DisplayName("EmptySegment.next() 应返回 -1")
    void emptySegmentShouldReturnMinusOne() {
        assertEquals(-1L, EmptySegment.INSTANCE.next());
        // 多次调用仍返回 -1
        assertEquals(-1L, EmptySegment.INSTANCE.next());
    }

    @Test
    @DisplayName("EmptySegment.remaining() 应返回 0")
    void emptySegmentShouldReturnZeroRemaining() {
        assertEquals(0L, EmptySegment.INSTANCE.remaining());
    }

    @Test
    @DisplayName("EmptySegment.isExhausted() 应返回 true")
    void emptySegmentShouldBeExhausted() {
        assertTrue(EmptySegment.INSTANCE.isExhausted());
    }

    @Test
    @DisplayName("EmptySegment.usage() 应返回 1.0")
    void emptySegmentShouldReturnFullUsage() {
        assertEquals(1.0, EmptySegment.INSTANCE.usage(), 0.001);
    }

    @Test
    @DisplayName("EmptySegment.isInitialized() 应返回 false")
    void emptySegmentShouldNotBeInitialized() {
        assertFalse(EmptySegment.INSTANCE.isInitialized());
    }

    @Test
    @DisplayName("EmptySegment.getCurrent() 应返回 -1")
    void emptySegmentShouldReturnMinusOneCurrent() {
        assertEquals(-1L, EmptySegment.INSTANCE.getCurrent());
    }

    @Test
    @DisplayName("EmptySegment.used() 应返回 0")
    void emptySegmentShouldReturnZeroUsed() {
        assertEquals(0L, EmptySegment.INSTANCE.used());
    }

    @Test
    @DisplayName("EmptySegment.getLoadTime() 应返回 0")
    void emptySegmentShouldReturnZeroLoadTime() {
        assertEquals(0L, EmptySegment.INSTANCE.getLoadTime());
    }

    @Test
    @DisplayName("EmptySegment.toString() 应返回 'EmptySegment'")
    void emptySegmentToStringShouldReturnClassName() {
        assertEquals("EmptySegment", EmptySegment.INSTANCE.toString());
    }

    @Test
    @DisplayName("EmptySegment 应是 Segment 的子类")
    void emptySegmentShouldBeSubclassOfSegment() {
        assertTrue(EmptySegment.INSTANCE instanceof Segment);
    }
}
