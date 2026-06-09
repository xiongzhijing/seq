package com.ccb.jx.seq;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.buffer.Segment;
import com.ccb.jx.seq.core.CachedSequence;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 诊断测试：精确定位小步长并发场景下的重复值来源。
 */
@SpringJUnitConfig(SequenceTestBase.TestConfiguration.class)
@Sql(scripts = "classpath:sql/init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("诊断测试：小步长并发重复值定位")
class DiagnosticDuplicateTest {

    @Autowired
    private CachedSequence cachedSequence;

    @Autowired
    private SequenceRepository sequenceRepository;

    @Test
    @DisplayName("诊断：单线程小步长 100 次，值应全部唯一")
    void singleThreadSmallStep() {
        Set<Long> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long val = cachedSequence.nextVal("TEST_CACHED_SMALL");
            assertThat(values.add(val))
                    .as("第 %d 次调用值 %d 不应重复", i, val)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("诊断：2 线程小步长各 10 次，值应全部唯一")
    void twoThreadSmallStep() throws Exception {
        int threadCount = 2;
        int callsPerThread = 10;

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Long> allValues = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < callsPerThread; i++) {
                        long val = cachedSequence.nextVal("TEST_CACHED_SMALL");
                        allValues.add(val);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }, "diag-worker-" + threadId).start();
        }

        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isEqualTo(0);

        Set<Long> uniqueValues = new HashSet<>(allValues);
        if (uniqueValues.size() != allValues.size()) {
            // 找出重复值
            Set<Long> seen = new HashSet<>();
            Set<Long> duplicates = new HashSet<>();
            for (Long v : allValues) {
                if (!seen.add(v)) {
                    duplicates.add(v);
                }
            }
            System.out.println("[DIAG] Duplicate values: " + duplicates);
            System.out.println("[DIAG] Total values: " + allValues.size() + ", unique: " + uniqueValues.size());
            System.out.println("[DIAG] All values sorted: " + new ArrayList<>(new TreeSet<>(allValues)));
        }
        assertThat(uniqueValues).as("应产生 %d 个唯一值", threadCount * callsPerThread).hasSize(threadCount * callsPerThread);
    }

    @Test
    @DisplayName("诊断：50 线程小步长各 30 次，值应全部唯一")
    void fiftyThreadSmallStep() throws Exception {
        int threadCount = 50;
        int callsPerThread = 30;

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Long> allValues = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < callsPerThread; i++) {
                        long val = cachedSequence.nextVal("TEST_CACHED_SMALL");
                        allValues.add(val);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }, "diag-50t-worker-" + t).start();
        }

        assertThat(doneLatch.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isEqualTo(0);

        Set<Long> uniqueValues = new HashSet<>(allValues);
        if (uniqueValues.size() != allValues.size()) {
            Set<Long> seen = new HashSet<>();
            Set<Long> duplicates = new HashSet<>();
            for (Long v : allValues) {
                if (!seen.add(v)) {
                    duplicates.add(v);
                }
            }
            System.out.println("[DIAG] 50T Duplicate values: " + duplicates);
            System.out.println("[DIAG] 50T Total values: " + allValues.size() + ", unique: " + uniqueValues.size());
            // 检查重复值是否来自号段边界
            for (Long dup : duplicates) {
                System.out.println("[DIAG] Duplicate value " + dup + " appears in ranges: ");
                for (int i = 0; i < allValues.size(); i++) {
                    if (allValues.get(i).equals(dup)) {
                        System.out.println("[DIAG]   index=" + i);
                    }
                }
            }
        }
        assertThat(uniqueValues).as("应产生 %d 个唯一值", threadCount * callsPerThread).hasSize(threadCount * callsPerThread);
    }

    @Test
    @DisplayName("诊断：大步长 50 线程各 20 次，值应全部唯一")
    void largeStepConcurrent() throws Exception {
        int threadCount = 50;
        int callsPerThread = 20;

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        Set<Long> allValues = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < callsPerThread; i++) {
                        long val = cachedSequence.nextVal("TEST_CACHED");
                        if (!allValues.add(val)) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }, "diag-large-" + t).start();
        }

        assertThat(doneLatch.await(120, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).as("不应有重复值或异常").isEqualTo(0);
        assertThat(allValues).as("应产生 %d 个唯一值", threadCount * callsPerThread).hasSize(threadCount * callsPerThread);
    }
}
