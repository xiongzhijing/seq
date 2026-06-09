/*
 * Copyright (C) 2026 mysql-sequence
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ccb.jx.seq;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.core.CachedSequence;
import com.ccb.jx.seq.core.SequenceService;
import com.ccb.jx.seq.core.ServiceState;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 高并发压测：100 线程并发，验证竞态处理。
 * <p>
 * 监控维度：
 * <ul>
 *   <li>值唯一性 — 无重复值</li>
 *   <li>延迟分布 — P50/P95/P99/Max</li>
 *   <li>错误分类 — 重复值、超时、异常、线程池拒绝</li>
 *   <li>活循环监控 — stale-reset 频率</li>
 *   <li>吞吐量 — TPS</li>
 * </ul>
 *
 * @author mysql-sequence
 */
@SpringJUnitConfig(SequenceTestBase.TestConfiguration.class)
@Sql(scripts = "classpath:sql/init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("100线程并发压测：竞态分析与性能监控")
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class ConcurrencyStressTest {

    private static final Properties STRESS_PROPS = new Properties();

    @BeforeAll
    static void loadStressConfig() throws IOException {
        try (InputStream is = ConcurrencyStressTest.class.getClassLoader()
                .getResourceAsStream("stress-test.properties")) {
            if (is != null) {
                STRESS_PROPS.load(is);
            }
        }
    }

    private static int prop(String key, int defaultValue) {
        return Integer.parseInt(STRESS_PROPS.getProperty(key, String.valueOf(defaultValue)));
    }

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private CachedSequence cachedSequence;

    @Autowired
    private SequenceRepository sequenceRepository;

    @BeforeEach
    void clearBufferMap() {
        cachedSequence.getLoadedSequenceNames().clear();
        // 重置关闭标志，防止 shutdown 测试影响后续测试
        // ServiceState 是单向枚举（RUNNING→SHUTTING_DOWN→SHUTDOWN），测试中需要通过反射重置
        resetServiceState(cachedSequence, ServiceState.RUNNING);
    }

    /**
     * 通过反射重置 CachedSequence 的 ServiceState，用于测试间状态隔离。
     * <p>
     * 生产代码中 ServiceState 是单向不可逆的（RUNNING→SHUTTING_DOWN→SHUTDOWN），
     * 但测试场景需要在每个测试方法前重置为 RUNNING，防止 shutdown 测试污染后续测试。
     * </p>
     */
    private static void resetServiceState(CachedSequence cachedSequence, ServiceState targetState) {
        try {
            java.lang.reflect.Field stateField = CachedSequence.class.getDeclaredField("state");
            stateField.setAccessible(true);
            stateField.set(cachedSequence, targetState);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset CachedSequence state for test", e);
        }
    }

    // ==================== 监控工具 ====================

    /** 延迟统计器 */
    static class LatencyStats {
        private final LongAdder totalLatencyNs = new LongAdder();
        private final AtomicLong maxLatencyNs = new AtomicLong(0);
        private final AtomicLong sampleCount = new AtomicLong(0);
        private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        void record(long latencyNs) {
            totalLatencyNs.add(latencyNs);
            sampleCount.incrementAndGet();
            maxLatencyNs.updateAndGet(max -> Math.max(max, latencyNs));
            // 采样：仅记录部分延迟用于分位数计算（避免大内存开销）
            if (sampleCount.get() % 10 == 0 || sampleCount.get() < 100) {
                latencies.add(latencyNs);
            }
        }

        void printSummary(String label) {
            long count = sampleCount.get();
            if (count == 0) {
                System.out.println("  " + label + ": 无数据");
                return;
            }
            long avgNs = totalLatencyNs.sum() / count;
            long maxNs = maxLatencyNs.get();

            Collections.sort(latencies);
            long p50 = getPercentile(latencies, 50);
            long p95 = getPercentile(latencies, 95);
            long p99 = getPercentile(latencies, 99);

            System.out.println("  " + label + " (samples=" + count + "):");
            System.out.println("    avg=" + formatNs(avgNs) +
                    " p50=" + formatNs(p50) +
                    " p95=" + formatNs(p95) +
                    " p99=" + formatNs(p99) +
                    " max=" + formatNs(maxNs));
        }

        private long getPercentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) return 0;
            int idx = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
            idx = Math.max(0, Math.min(idx, sorted.size() - 1));
            return sorted.get(idx);
        }

        private String formatNs(long ns) {
            if (ns < 1_000) return ns + "ns";
            if (ns < 1_000_000) return String.format("%.1fμs", ns / 1000.0);
            if (ns < 1_000_000_000) return String.format("%.1fms", ns / 1_000_000.0);
            return String.format("%.2fs", ns / 1_000_000_000.0);
        }
    }

    /** 错误分类计数器 */
    static class ErrorTracker {
        private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
        private final AtomicLong totalErrors = new AtomicLong(0);

        void increment(String category) {
            counters.computeIfAbsent(category, k -> new AtomicInteger(0)).incrementAndGet();
            totalErrors.incrementAndGet();
        }

        void printSummary() {
            if (totalErrors.get() == 0) {
                System.out.println("  错误: 无");
                return;
            }
            System.out.println("  错误总数: " + totalErrors.get());
            for (java.util.Map.Entry<String, AtomicInteger> entry : counters.entrySet()) {
                System.out.println("    " + entry.getKey() + ": " + entry.getValue().get());
            }
        }

        long getTotal() {
            return totalErrors.get();
        }

        int get(String category) {
            AtomicInteger counter = counters.get(category);
            return counter != null ? counter.get() : 0;
        }
    }

    // ==================== STRICT 模式 ====================

    @Nested
    @DisplayName("STRICT 模式压测")
    class StrictConcurrencyTests {

        @Test
        @DisplayName("STRICT 串行 nextVal，值应严格连续无间隙")
        void strictSerialContinuous() {
            int callCount = prop("stress.strict.serial.call-count", 500);
            List<Long> values = new ArrayList<>();
            for (int i = 0; i < callCount; i++) {
                values.add(sequenceService.nextVal("TEST_STRICT"));
            }
            for (int i = 0; i < values.size(); i++) {
                assertThat(values.get(i)).as("第 %d 个值应为 %d", i, 2L + i).isEqualTo(2L + i);
            }
            System.out.println("[STRICT] 串行测试通过: callCount=" + callCount);
        }

        @Test
        @DisplayName("STRICT 串行后 max_id 应等于初始值 + 调用次数")
        void strictSerialMaxIdConsistency() {
            int callCount = prop("stress.strict.max-id.call-count", 250);
            long initialMaxId = sequenceRepository.selectConfigForUpdate("TEST_STRICT").getMaxId();
            for (int i = 0; i < callCount; i++) {
                sequenceService.nextVal("TEST_STRICT");
            }
            long finalMaxId = sequenceRepository.selectConfigForUpdate("TEST_STRICT").getMaxId();
            assertThat(finalMaxId).isEqualTo(initialMaxId + callCount);
        }
    }

    // ==================== CACHED 模式并发 ====================

    @Nested
    @DisplayName("CACHED 模式并发压测")
    class CachedConcurrencyTests {

        @Test
        @DisplayName("100线程并发 CACHED nextVal，值唯一，监控延迟分布")
        void cachedConcurrentNoDuplicate() throws Exception {
            int threadCount = prop("stress.cached.concurrent.thread-count", 100);
            int callsPerThread = prop("stress.cached.concurrent.calls-per-thread", 500);
            int barrierTimeout = prop("stress.cached.concurrent.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.cached.concurrent.await-timeout-seconds", 300);
            int totalCalls = threadCount * callsPerThread;

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            Set<Long> allValues = Collections.synchronizedSet(new HashSet<>());
            ErrorTracker errors = new ErrorTracker();
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            LatencyStats latency = new LatencyStats();

            System.out.println("\n[TEST] CACHED 并发唯一性: threads=" + threadCount + ", calls=" + totalCalls);

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            long start = System.nanoTime();
                            long val = cachedSequence.nextVal("TEST_CACHED");
                            long elapsed = System.nanoTime() - start;
                            latency.record(elapsed);

                            if (!allValues.add(val)) {
                                errors.increment("duplicate_value");
                            }
                        }
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }, "cached-worker-" + t).start();
            }

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();

            long elapsed = System.currentTimeMillis();
            latency.printSummary("延迟分布");
            errors.printSummary();
            System.out.printf("  TPS=%.0f%n", (double) totalCalls / elapsed * 1000);

            assertThat(errors.getTotal()).as("不应有重复值或异常").isEqualTo(0);
            assertThat(allValues).as("应产生 %d 个唯一值", totalCalls).hasSize(totalCalls);
        }

        @Test
        @DisplayName("CACHED 小步长高并发：触发大量号段切换，值仍唯一")
        void cachedSmallStepConcurrentSwitch() throws Exception {
            int threadCount = prop("stress.cached.small-step.thread-count", 100);
            int callsPerThread = prop("stress.cached.small-step.calls-per-thread", 200);
            int barrierTimeout = prop("stress.cached.small-step.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.cached.small-step.await-timeout-seconds", 300);
            int totalCalls = threadCount * callsPerThread;

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            Set<Long> allValues = Collections.synchronizedSet(new HashSet<>());
            ErrorTracker errors = new ErrorTracker();
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            LatencyStats latency = new LatencyStats();

            System.out.println("\n[TEST] CACHED 小步长切换: threads=" + threadCount + ", calls=" + totalCalls);

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            long start = System.nanoTime();
                            long val = cachedSequence.nextVal("TEST_CACHED_SMALL");
                            long elapsed = System.nanoTime() - start;
                            latency.record(elapsed);

                            if (!allValues.add(val)) {
                                errors.increment("duplicate_value");
                            }
                        }
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }, "cached-small-worker-" + t).start();
            }

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();

            latency.printSummary("延迟分布");
            errors.printSummary();

            // 监控 stale-reset 情况
            DoubleBuffer buffer = cachedSequence.getBuffer("TEST_CACHED_SMALL");
            if (buffer != null) {
                int staleResets = buffer.getStaleResetCount();
                System.out.println("  stale-reset-count=" + staleResets);
            }

            assertThat(errors.getTotal()).as("不应有重复值或异常").isEqualTo(0);
            assertThat(allValues).as("应产生 %d 个唯一值", totalCalls).hasSize(totalCalls);
        }
    }

    // ==================== DoubleBuffer 切换压测 ====================

    @Nested
    @DisplayName("DoubleBuffer 切换压测")
    class DoubleBufferSwitchTests {

        @Test
        @DisplayName("100线程高并发触发号段切换：小步长强制频繁切换")
        void highConcurrencyFrequentSwitch() throws Exception {
            int threadCount = prop("stress.double-buffer.thread-count", 100);
            int callsPerThread = prop("stress.double-buffer.calls-per-thread", 200);
            int barrierTimeout = prop("stress.double-buffer.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.double-buffer.await-timeout-seconds", 300);
            int totalCalls = threadCount * callsPerThread;

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            Set<Long> allValues = Collections.synchronizedSet(new HashSet<>());
            ErrorTracker errors = new ErrorTracker();
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            LatencyStats latency = new LatencyStats();

            System.out.println("\n[TEST] DoubleBuffer 频繁切换: threads=" + threadCount + ", calls=" + totalCalls);

            long startTime = System.currentTimeMillis();

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            long start = System.nanoTime();
                            long val = cachedSequence.nextVal("TEST_CACHED_SMALL");
                            long elapsed = System.nanoTime() - start;
                            latency.record(elapsed);

                            if (!allValues.add(val)) {
                                errors.increment("duplicate_value");
                            }
                        }
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }, "switch-worker-" + t).start();
            }

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();
            long elapsed = System.currentTimeMillis() - startTime;

            double tps = (double) totalCalls / elapsed * 1000;
            System.out.printf("  elapsed=%dms, TPS=%.0f%n", elapsed, tps);
            latency.printSummary("延迟分布");
            errors.printSummary();

            assertThat(errors.getTotal()).as("不应有重复值或异常").isEqualTo(0);
            assertThat(allValues).as("应产生 %d 个唯一值", totalCalls).hasSize(totalCalls);
        }
    }

    // ==================== SequenceService 路由压测 ====================

    @Nested
    @DisplayName("SequenceService 路由混合压测")
    class MixedModeTests {

        @Test
        @DisplayName("100线程通过 SequenceService 路由调用，值唯一")
        void cachedViaServiceConcurrent() throws Exception {
            int threadCount = prop("stress.service-mixed.thread-count", 100);
            int callsPerThread = prop("stress.service-mixed.calls-per-thread", 500);
            int barrierTimeout = prop("stress.service-mixed.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.service-mixed.await-timeout-seconds", 300);
            int totalCalls = threadCount * callsPerThread;

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            Set<Long> allValues = Collections.synchronizedSet(new HashSet<>());
            ErrorTracker errors = new ErrorTracker();
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            LatencyStats latency = new LatencyStats();

            System.out.println("\n[TEST] Service 路由并发: threads=" + threadCount + ", calls=" + totalCalls);

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            long start = System.nanoTime();
                            long val = sequenceService.nextVal("TEST_CACHED");
                            long elapsed = System.nanoTime() - start;
                            latency.record(elapsed);

                            if (!allValues.add(val)) {
                                errors.increment("duplicate_value");
                            }
                        }
                    } catch (SequenceException e) {
                        errors.increment("SequenceErrorCode." + e.getErrorCode().name());
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }, "service-cached-" + t).start();
            }

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();

            latency.printSummary("延迟分布");
            errors.printSummary();

            assertThat(errors.getTotal()).as("不应有重复值或异常").isEqualTo(0);
            assertThat(allValues).as("应产生 %d 个唯一值", totalCalls).hasSize(totalCalls);
        }
    }

    // ==================== 吞吐量基准 ====================

    @Nested
    @DisplayName("吞吐量基准测试")
    class ThroughputBenchmarkTests {

        @Test
        @DisplayName("CACHED 模式吞吐量基准：单线程")
        void cachedSingleThreadThroughput() {
            int totalCalls = prop("stress.throughput.cached-single.calls", 100_000);
            long startTime = System.nanoTime();

            for (int i = 0; i < totalCalls; i++) {
                cachedSequence.nextVal("TEST_CACHED");
            }

            long elapsed = System.nanoTime() - startTime;
            double tps = (double) totalCalls / elapsed * 1_000_000_000;

            System.out.printf("\n[BENCHMARK] CACHED 单线程: calls=%d, elapsed=%.1fms, TPS=%.0f%n",
                    totalCalls, elapsed / 1_000_000.0, tps);

            assertThat(tps).as("CACHED 单线程 TPS 应 >= 10000").isGreaterThanOrEqualTo(10_000);
        }

        @Test
        @DisplayName("CACHED 模式吞吐量基准：100线程并发")
        void cachedMultiThreadThroughput() throws Exception {
            int threadCount = prop("stress.throughput.cached-multi.thread-count", 100);
            int callsPerThread = prop("stress.throughput.cached-multi.calls-per-thread", 5000);
            int barrierTimeout = prop("stress.throughput.cached-multi.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.throughput.cached-multi.await-timeout-seconds", 300);
            int totalCalls = threadCount * callsPerThread;

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ErrorTracker errors = new ErrorTracker();

            System.out.println("\n[BENCHMARK] CACHED 多线程: threads=" + threadCount + ", totalCalls=" + totalCalls);

            long globalStart = System.nanoTime();

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            cachedSequence.nextVal("TEST_CACHED");
                        }
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();
            long globalElapsed = System.nanoTime() - globalStart;
            double tps = (double) totalCalls / globalElapsed * 1_000_000_000;

            System.out.printf("  elapsed=%.1fms, TPS=%.0f, errors=%d%n",
                    globalElapsed / 1_000_000.0, tps, errors.getTotal());
        }

        @Test
        @DisplayName("STRICT 模式吞吐量基准：单线程")
        void strictSingleThreadThroughput() {
            int totalCalls = prop("stress.throughput.strict-single.calls", 500);
            long startTime = System.nanoTime();

            for (int i = 0; i < totalCalls; i++) {
                sequenceService.nextVal("TEST_STRICT");
            }

            long elapsed = System.nanoTime() - startTime;
            double tps = (double) totalCalls / elapsed * 1_000_000_000;

            System.out.printf("\n[BENCHMARK] STRICT 单线程: calls=%d, elapsed=%.1fms, TPS=%.0f%n",
                    totalCalls, elapsed / 1_000_000.0, tps);
        }
    }

    // ==================== DoubleBuffer 状态验证 ====================

    @Nested
    @DisplayName("DoubleBuffer 状态验证")
    class DoubleBufferStateTests {

        @Test
        @DisplayName("100线程并发后 DoubleBuffer 状态一致：current 非空，remaining >= 0")
        void bufferStateConsistentAfterConcurrent() throws Exception {
            int threadCount = prop("stress.buffer-state.thread-count", 100);
            int callsPerThread = prop("stress.buffer-state.calls-per-thread", 500);
            int barrierTimeout = prop("stress.buffer-state.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.buffer-state.await-timeout-seconds", 300);

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ErrorTracker errors = new ErrorTracker();

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            cachedSequence.nextVal("TEST_CACHED");
                        }
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();

            DoubleBuffer buffer = cachedSequence.getBuffer("TEST_CACHED");
            assertThat(buffer).isNotNull();
            assertThat(buffer.getCurrent()).as("current Segment 不应为空").isNotNull();
            assertThat(buffer.remaining()).as("remaining 应 >= 0").isGreaterThanOrEqualTo(0);

            int staleResets = buffer.getStaleResetCount();
            System.out.println("\n[STATE] DoubleBuffer 状态: remaining=" + buffer.remaining() +
                    ", staleResetCount=" + staleResets);
            errors.printSummary();
        }
    }

    // ==================== 专项并发测试 ====================

    @Nested
    @DisplayName("专项并发测试")
    class SpecialConcurrencyTests {

        @Test
        @DisplayName("混合 STRICT + CACHED 并发：验证互不干扰")
        void mixedStrictCachedConcurrent() throws Exception {
            int threadCount = 100;
            int callsPerThread = 100;
            int barrierTimeout = 30;
            int awaitTimeout = 300;
            int halfThreads = threadCount / 2;

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            Set<Long> strictValues = Collections.synchronizedSet(new HashSet<>());
            Set<Long> cachedValues = Collections.synchronizedSet(new HashSet<>());
            ErrorTracker errors = new ErrorTracker();
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            LatencyStats strictLatency = new LatencyStats();
            LatencyStats cachedLatency = new LatencyStats();

            System.out.println("\n[TEST] 混合 STRICT+CACHED: threads=" + threadCount);

            for (int t = 0; t < threadCount; t++) {
                final boolean isStrict = t < halfThreads;
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            long start = System.nanoTime();
                            if (isStrict) {
                                long val = sequenceService.nextVal("TEST_STRICT");
                                strictLatency.record(System.nanoTime() - start);
                                if (!strictValues.add(val)) {
                                    errors.increment("strict_duplicate");
                                }
                            } else {
                                long val = cachedSequence.nextVal("TEST_CACHED");
                                cachedLatency.record(System.nanoTime() - start);
                                if (!cachedValues.add(val)) {
                                    errors.increment("cached_duplicate");
                                }
                            }
                        }
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }, "mixed-worker-" + t + (isStrict ? "-S" : "-C")).start();
            }

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();

            System.out.println("  STRICT: " + strictValues.size() + " unique values");
            System.out.println("  CACHED: " + cachedValues.size() + " unique values");
            strictLatency.printSummary("STRICT 延迟");
            cachedLatency.printSummary("CACHED 延迟");
            errors.printSummary();

            assertThat(errors.getTotal()).isEqualTo(0);
        }

        @Test
        @DisplayName("极端争用：100线程×1000次同一序列，强制频繁切换")
        void extremeContention() throws Exception {
            int threadCount = prop("stress.extreme-contention.thread-count", 100);
            int callsPerThread = prop("stress.extreme-contention.calls-per-thread", 1000);
            int barrierTimeout = prop("stress.extreme-contention.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.extreme-contention.await-timeout-seconds", 600);
            int totalCalls = threadCount * callsPerThread;

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            Set<Long> allValues = Collections.synchronizedSet(new HashSet<>());
            ErrorTracker errors = new ErrorTracker();
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            LatencyStats latency = new LatencyStats();
            AtomicInteger switchCount = new AtomicInteger(0);

            System.out.println("\n[TEST] 极端争用: threads=" + threadCount + ", calls=" + totalCalls);

            long startTime = System.currentTimeMillis();

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            long start = System.nanoTime();
                            long val = cachedSequence.nextVal("TEST_CACHED_SMALL");
                            long elapsed = System.nanoTime() - start;
                            latency.record(elapsed);

                            if (!allValues.add(val)) {
                                errors.increment("duplicate_value");
                            }
                        }
                        switchCount.incrementAndGet();
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }, "extreme-worker-" + t).start();
            }

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();
            long elapsed = System.currentTimeMillis() - startTime;

            double tps = (double) totalCalls / elapsed * 1000;
            System.out.printf("  elapsed=%dms, TPS=%.0f, threads_completed=%d%n",
                    elapsed, tps, switchCount.get());
            latency.printSummary("延迟分布");
            errors.printSummary();

            DoubleBuffer buffer = cachedSequence.getBuffer("TEST_CACHED_SMALL");
            if (buffer != null) {
                System.out.println("  stale-reset-count=" + buffer.getStaleResetCount());
            }

            assertThat(errors.getTotal()).as("不应有重复值或异常").isEqualTo(0);
            assertThat(allValues).as("应产生 %d 个唯一值", totalCalls).hasSize(totalCalls);
        }

        @Test
        @DisplayName("配置缓存刷新并发：nextVal 与 configCache 刷新并行")
        void configRefreshConcurrent() throws Exception {
            int threadCount = prop("stress.config-refresh-concurrent.thread-count", 100);
            int callsPerThread = prop("stress.config-refresh-concurrent.calls-per-thread", 200);
            int barrierTimeout = prop("stress.config-refresh-concurrent.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.config-refresh-concurrent.await-timeout-seconds", 300);
            int totalCalls = threadCount * callsPerThread;

            CyclicBarrier barrier = new CyclicBarrier(threadCount + 1); // +1 for refresh thread
            Set<Long> allValues = Collections.synchronizedSet(new HashSet<>());
            ErrorTracker errors = new ErrorTracker();
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            LatencyStats latency = new LatencyStats();
            AtomicInteger refreshCount = new AtomicInteger(0);

            System.out.println("\n[TEST] 配置刷新并发: threads=" + threadCount);

            // 启动并发 nextVal 线程
            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            long start = System.nanoTime();
                            long val = sequenceService.nextVal("TEST_CACHED");
                            long elapsed = System.nanoTime() - start;
                            latency.record(elapsed);

                            if (!allValues.add(val)) {
                                errors.increment("duplicate_value");
                            }
                        }
                    } catch (SequenceException e) {
                        errors.increment("SequenceErrorCode." + e.getErrorCode().name());
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }, "refresh-concurrent-" + t).start();
            }

            // 启动配置刷新线程
            Thread refreshThread = new Thread(() -> {
                try {
                    barrier.await(barrierTimeout, TimeUnit.SECONDS);
                    for (int i = 0; i < 5; i++) {
                        sequenceService.clearCache();
                        refreshCount.incrementAndGet();
                        Thread.sleep(500);
                    }
                } catch (Exception e) {
                    errors.increment("refresh_exception");
                }
            }, "config-refresh-thread");
            refreshThread.start();

            assertThat(doneLatch.await(awaitTimeout, TimeUnit.SECONDS)).isTrue();

            System.out.println("  config refreshes executed=" + refreshCount.get());
            latency.printSummary("延迟分布");
            errors.printSummary();

            // 允许有 SHUTTING_DOWN 或 SEQ_NOT_FOUND 错误（缓存刷新期间的竞态）
            // 但不允许重复值
            assertThat(allValues.size()).as("应产生唯一值").isGreaterThan(0);
            // 确保没有严重错误
            long criticalErrors = errors.getTotal() - errors.get("SequenceErrorCode.SHUTTING_DOWN");
            assertThat(criticalErrors).as("不应有严重错误（SHUTTING_DOWN除外）").isEqualTo(0);
        }

        @Test
        @DisplayName("关闭时序：运行中触发 shutdown，验证 SHUTTING_DOWN 正确传播")
        void shutdownSequence() throws Exception {
            int threadCount = prop("stress.shutdown-sequence.thread-count", 100);
            int callsPerThread = prop("stress.shutdown-sequence.calls-per-thread", 500);
            int barrierTimeout = prop("stress.shutdown-sequence.barrier-timeout-seconds", 30);
            int awaitTimeout = prop("stress.shutdown-sequence.await-timeout-seconds", 300);
            int totalCalls = threadCount * callsPerThread;

            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            Set<Long> allValues = Collections.synchronizedSet(new HashSet<>());
            ErrorTracker errors = new ErrorTracker();
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger shuttingDownErrors = new AtomicInteger(0);
            LatencyStats latency = new LatencyStats();

            System.out.println("\n[TEST] 关闭时序: threads=" + threadCount);

            for (int t = 0; t < threadCount; t++) {
                new Thread(() -> {
                    try {
                        barrier.await(barrierTimeout, TimeUnit.SECONDS);
                        for (int i = 0; i < callsPerThread; i++) {
                            long start = System.nanoTime();
                            long val = cachedSequence.nextVal("TEST_CACHED");
                            long elapsed = System.nanoTime() - start;
                            latency.record(elapsed);

                            if (!allValues.add(val)) {
                                errors.increment("duplicate_value");
                            }
                        }
                    } catch (SequenceException e) {
                        if (e.getErrorCode() == SequenceErrorCode.SHUTTING_DOWN) {
                            shuttingDownErrors.incrementAndGet();
                        } else {
                            errors.increment("SequenceErrorCode." + e.getErrorCode().name());
                        }
                    } catch (Exception e) {
                        errors.increment("exception_" + e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                }, "shutdown-worker-" + t).start();
            }

            // 等待 1 秒后触发关闭
            Thread.sleep(1000);
            System.out.println("  [SHUTDOWN] Triggering shutdown...");
            cachedSequence.transitionTo(ServiceState.SHUTTING_DOWN);
            cachedSequence.persistUnusedSegments();

            boolean completed = doneLatch.await(awaitTimeout, TimeUnit.SECONDS);
            System.out.println("  [SHUTDOWN] All threads completed: " + completed);
            System.out.println("  [SHUTDOWN] SHUTTING_DOWN errors caught: " + shuttingDownErrors.get());
            System.out.println("  [SHUTDOWN] Unique values produced: " + allValues.size());
            latency.printSummary("延迟分布（不含SHUTTING_DOWN请求）");
            errors.printSummary();

            // 验证：应该有 SHUTTING_DOWN 错误，且无重复值
            assertThat(shuttingDownErrors.get()).as("应捕获 SHUTTING_DOWN 错误").isGreaterThan(0);
            // 验证没有重复值
            long nonShutdownErrors = errors.getTotal();
            assertThat(nonShutdownErrors).as("除SHUTTING_DOWN外不应有其他错误").isEqualTo(0);
        }
    }
}
