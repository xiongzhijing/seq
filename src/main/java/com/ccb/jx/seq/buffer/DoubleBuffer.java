
package com.ccb.jx.seq.buffer;

import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 双 Buffer 管理器。<p>
 * 持有两个 {@link Segment} 引用（current / standby），通过 volatile 保证可见性、
 * {@link ReentrantLock} 保证切换的线程安全。两个 Segment 交替使用，实现
 * 零等待切换，适用于 CACHED 模式的高吞吐序列分配。
 * </p>
 *
 * <h3>预加载策略</h3>
 * 当当前 Segment 的剩余比例低于 {@link #PRELOAD_THRESHOLD}（10%）时，
 * 触发备用 Segment 的预加载。预加载通过外部的异步任务提交器
 * （由 {@link #setAsyncTaskSubmitter(Runnable)} 设置）执行，不会阻塞
 * 业务线程。如果未设置异步提交器，则降级为同步加载。
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>{@code next()} 中的常规分配由 {@link Segment#next()} 的
 *       {@code AtomicLong.getAndIncrement()} 保证无锁安全</li>
 *   <li>Buffer 切换由 {@link #switchLock} 保证互斥</li>
 *   <li>{@code current}/{@code standby} 引用使用 {@code volatile} 保证
 *       跨线程可见性</li>
 * </ul>
 *
 * @author XZJ
 */
public class DoubleBuffer {

    private static final Logger log = LoggerFactory.getLogger(DoubleBuffer.class);

    /**
     * 预加载阈值：当前 Segment 剩余比例小于此值时触发异步加载。
     * 10% 的阈值在号段较大时（如 step=1000），剩余 100 个值时触发，
     * 预加载时间窗口充足。
     */
    private static final double PRELOAD_THRESHOLD = 0.1;

    /**
     * next() 方法最大重试次数。<p>
     * 防止 switchBuffer 持续返回 -1 导致无限循环，
     * 在数据库不可用或号段加载持续失败时快速失败。
     * </p>
     */
    private static final int MAX_RETRY = 3;

    /** 当前使用中的 Segment */
    private volatile Segment current;

    /** 备用 Segment */
    private volatile Segment standby;

    /** Buffer 切换锁 */
    private final ReentrantLock switchLock = new ReentrantLock();

    /** 号段加载函数（从数据库加载新号段） */
    private final Supplier<Segment> segmentSupplier;

    /** 序列名称（用于日志） */
    private final String seqName;

    /**
     * 预加载触发标记，使用 AtomicBoolean 保证仅触发一次。<p>
     * {@code true} 表示已触发过预加载（待 standby 加载完成后重置）。
     * </p>
     */
    private final AtomicBoolean preloadTriggered = new AtomicBoolean(false);

    /**
     * 活循环监控：stale preloadTriggered 重置计数。<p>
     * 当 {@link #preloadTriggered} 为 true 但 standby 仍为 null 时，
     * 说明异步任务被线程池丢弃，需要重置标记。此计数器记录重置次数，
     * 用于监控活循环风险（频率过高说明线程池容量不足或预加载逻辑异常）。
     * </p>
     */
    private final AtomicInteger staleResetCount = new AtomicInteger(0);

    /**
     * 异步任务提交器。<p>
     * 由外部（通常是 {@code CachedSequence}）通过 {@link #setAsyncTaskSubmitter(Runnable)}
     * 设置，用于将 {@link #loadStandby()} 提交到线程池异步执行。
     * 如果为 {@code null}，则 {@link #checkPreload()} 降级为同步加载。
     * </p>
     */
    private volatile Runnable asyncTaskSubmitter;

    /**
     * 使用指定的序列名称和号段加载函数构造双缓冲。
     *
     * @param seqName         序列名称，用于日志标识
     * @param segmentSupplier 号段加载函数，每次调用返回一个新号段
     */
    public DoubleBuffer(String seqName, Supplier<Segment> segmentSupplier) {
        this.seqName = seqName;
        this.segmentSupplier = segmentSupplier;
        // 使用 EmptySegment 替代 null（Null Object 模式）
        this.current = EmptySegment.INSTANCE;
        this.standby = EmptySegment.INSTANCE;
    }

    /**
     * 设置异步任务提交器。<p>
     * 由 {@code CachedSequence} 初始化时调用，将 {@link #loadStandby()} 的调用
     * 包装为异步任务提交到共享线程池。设置后可实现非阻塞预加载。
     * </p>
     *
     * @param asyncTaskSubmitter 异步任务提交器，接受一个 Runnable 并异步执行
     */
    public void setAsyncTaskSubmitter(Runnable asyncTaskSubmitter) {
        this.asyncTaskSubmitter = asyncTaskSubmitter;
    }

    /**
     * 获取下一个序列值。<p>
     * 优先从当前 Segment 分配；当前 Segment 耗尽时尝试切换到备用 Segment；
     * 备用不可用时同步加载新 Segment。
     * </p>
     * <p>
     * 使用循环重试机制：当分配返回 -1（号段耗尽）时，进入
     * {@link #switchBuffer()} 切换号段。switchBuffer 的双重检查路径
     * 也可能返回 -1（新号段被其他线程耗尽），此时需要循环重试。
     * </p>
     *
     * @return 序列值
     * @throws SequenceException 如果号段加载失败
     */
    public long next() {
        // 延迟初始化：EmptySegment.next() 返回 -1，触发首次加载
        if (current instanceof EmptySegment) {
            switchLock.lock();
            try {
                if (current instanceof EmptySegment) {
                    current = loadSegment();
                }
            } finally {
                switchLock.unlock();
            }
        }

        int retryCount = 0;
        while (retryCount < MAX_RETRY) {
            long value = current.next();
            if (value >= 0) {
                checkPreload();
                return value;
            }
            // 当前 Segment 耗尽，进入 switchBuffer 切换
            log.debug("[BUFFER] Current segment exhausted for '{}', switching buffer, thread={}",
                    seqName, Thread.currentThread().getName());
            value = switchBuffer();
            if (value >= 0) {
                return value;
            }
            retryCount++;
            // switchBuffer 返回 -1（新号段被其他线程耗尽），循环重试
            log.debug("[BUFFER] switchBuffer returned -1 for '{}' (retry {}/{}), thread={}",
                    seqName, retryCount, MAX_RETRY, Thread.currentThread().getName());
        }
        throw new SequenceException(SequenceErrorCode.SEGMENT_LOAD_FAILED,
                "Failed to allocate value after " + MAX_RETRY + " retries for sequence: " + seqName);
    }

    /**
     * 判断当前 Segment 是否需要预加载备用号段。<p>
     * 两个条件用 OR 组合，任一满足即触发：
     * <ol>
     *   <li>使用率达到 {@link #PRELOAD_THRESHOLD} 阈值（默认 90%）</li>
     *   <li>剩余数量小于 {@code Math.max(50, 号段总大小 × 0.3)}</li>
     * </ol>
     * 条件②主要解决小步长场景下条件①窗口过小的问题。
     * </p>
     *
     * @return 如果满足预加载条件返回 {@code true}
     */
    private boolean needsPreload() {
        Segment seg = current;
        if (seg instanceof EmptySegment) {
            return false;
        }
        long totalSize = seg.getEnd() - seg.getStart() + 1;
        long remaining = seg.remaining();

        // 条件①：使用率达到阈值
        boolean usageThreshold = seg.usage() >= (1 - PRELOAD_THRESHOLD);
        // 条件②：剩余数量小于 max(50, 总大小 × 30%)
        boolean remainingThreshold = remaining < Math.max(50L, (long) (totalSize * 0.3));
        return usageThreshold || remainingThreshold;
    }

    /**
     * 检查是否需要触发异步预加载。<p>
     * 使用 {@link #needsPreload()} 判断是否满足预加载条件，并使用
     * {@link #preloadTriggered} 的 CAS 操作确保仅提交一次异步任务，
     * 避免多线程并发重复触发。
     * 优先通过外部线程池异步执行，未设置时降级为同步加载。
     * </p>
     */
    private void checkPreload() {
        if (!needsPreload()) {
            return;
        }
        // 如果 preloadTriggered 已被设置但 standby 仍为 null，
        // 说明异步任务可能被线程池丢弃（DiscardPolicy），需要重置标记。
        // 只重置标记，不立即重新触发，避免在极端并发下形成"重置→触发→拒绝→重置"的活循环。
        // 下一次 next() 调用会自然重试。
        if (preloadTriggered.get() && standby instanceof EmptySegment) {
            if (preloadTriggered.compareAndSet(true, false)) {
                int count = staleResetCount.incrementAndGet();
                log.info("[BUFFER] stale-preload-reset: seqName={}, staleResetCount={}, " +
                        "usage={}%, remaining={}, thread={}",
                        seqName, count,
                        String.format("%.0f", current.usage() * 100),
                        current.remaining(), Thread.currentThread().getName());
                // 活循环预警：单次号段生命周期内重置超过 10 次，说明线程池严重不足
                if (count > 10) {
                    log.warn("[BUFFER] stale-preload-reset WARNING: seqName={} reset {} times, " +
                            "async thread pool may be undersized (current max=5), " +
                            "consider increasing pool size or step",
                            seqName, count);
                }
                return;  // 本轮只重置，不触发，让下次 next() 自然重试
            }
        }
        // CAS 确保仅一个线程能触发异步加载
        if (!preloadTriggered.compareAndSet(false, true)) {
            return;
        }
        log.debug("[BUFFER] Preload triggered for '{}': usage={}, remaining={}, standby=null, thread={}",
                seqName, String.format("%.0f%%", current.usage() * 100),
                current.remaining(), Thread.currentThread().getName());
        triggerAsyncLoad();
    }

    /**
     * 触发异步加载：优先使用外部线程池，降级为同步加载。
     */
    private void triggerAsyncLoad() {
        Runnable submitter = this.asyncTaskSubmitter;
        if (submitter != null) {
            try {
                log.debug("[BUFFER] Submitting async load for '{}', thread={}", seqName, Thread.currentThread().getName());
                // 通过外部线程池异步加载，不阻塞当前业务线程
                submitter.run();
            } catch (Exception e) {
                log.error("[BUFFER] Failed to submit async load for '{}': {}", seqName, e.getMessage());
                preloadTriggered.set(false);  // 重置标记，允许下次重试
            }
        } else {
            // 降级：当前线程同步加载（测试环境或未配置线程池）
            log.debug("[BUFFER] No async submitter, falling back to sync load for '{}'", seqName);
            loadStandby();
        }
    }

    /**
     * 同步加载备用 Segment。<p>
     * 此方法可被外部线程池异步调用（通过 {@link #asyncTaskSubmitter}），
     * 也可被 {@link #switchBuffer()} 在切换时同步调用。
     * 使用 {@link #switchLock} 保护，防止多线程并发通过 {@code standby != null}
     * 检查后重复加载号段。
     * </p>
     */
    public void loadStandby() {
        switchLock.lock();
        try {
            if (!(standby instanceof EmptySegment)) {
                log.debug("[BUFFER] loadStandby skipped, standby already exists for '{}'", seqName);
                return;
            }
            log.debug("[BUFFER] loadStandby acquiring segment for '{}', thread={}",
                    seqName, Thread.currentThread().getName());
            Segment segment = segmentSupplier.get();
            if (segment != null) {
                standby = segment;
                log.debug("[BUFFER] Standby segment loaded for '{}': {}, thread={}",
                        seqName, segment, Thread.currentThread().getName());
            }
        } catch (Exception e) {
            log.error("[BUFFER] Failed to load standby segment for '{}': {}", seqName, e.getMessage(), e);
            preloadTriggered.set(false);
        } finally {
            switchLock.unlock();
        }
    }

    /**
     * 切换 Buffer（从当前切换到备用）。<p>
     * 使用 {@link #switchLock} 保证切换操作的原子性和线程安全。
     * 采用双重检查（double-check）模式：先检查 {@code current.isExhausted()}，
     * 加锁后再检查一次，避免不必要的锁竞争。
     * </p>
     *
     * @return 序列值
     * @throws SequenceException 如果备用和同步加载均失败
     */
    private long switchBuffer() {
        log.debug("[BUFFER] switchBuffer enter for '{}', acquiring lock, thread={}",
                seqName, Thread.currentThread().getName());
        switchLock.lock();
        try {
            // 双重检查：加锁后重新确认当前 Segment 是否确实耗尽
            if (!current.isExhausted()) {
                log.debug("[BUFFER] switchBuffer double-check: current not exhausted for '{}', another thread already switched",
                        seqName);
                return current.next();
            }

            if (standby.isInitialized()) {
                // 切换备用为当前
                Segment oldCurrent = current;
                current = standby;
                standby = EmptySegment.INSTANCE;
                // 切换成功后重置预加载标记，允许下一轮预加载触发
                preloadTriggered.set(false);
                // 重置活循环监控计数（新号段开始，旧计数不再有意义）
                staleResetCount.set(0);
                log.info("[BUFFER] Buffer switched for '{}': old=[{}, {}], new=[{}, {}], thread={}",
                        seqName, oldCurrent.getStart(), oldCurrent.getEnd(),
                        current.getStart(), current.getEnd(), Thread.currentThread().getName());

                // 从新当前 Segment 获取
                long value = current.next();
                if (value >= 0) {
                    return value;
                }

                // 理论上不应发生：standby 已初始化但 next() 返回 -1
                log.warn("[BUFFER] Standby segment for '{}' returned -1 immediately after switch, "
                        + "falling back to sync load", seqName);
            }

            // 备用不可用，同步加载新 Segment
            log.warn("[BUFFER] Standby not available for '{}', performing sync load, thread={}",
                    seqName, Thread.currentThread().getName());
            Segment newSegment = loadSegment();
            // 同步加载后重置预加载标记，允许新一轮预加载触发
            preloadTriggered.set(false);
            current = newSegment;
            log.info("[BUFFER] Sync load completed for '{}': new segment=[{}, {}]",
                    seqName, newSegment.getStart(), newSegment.getEnd());
            staleResetCount.set(0);
            return current.next();

        } finally {
            switchLock.unlock();
        }
    }

    /**
     * 同步加载新 Segment。<p>
     * 由 {@link #switchBuffer()} 在当前备用不可用时调用。
     * 此操作为同步阻塞，会等待数据库号段分配完成。
     * </p>
     *
     * @return 新加载的号段
     * @throws SequenceException 如果号段加载失败
     */
    private Segment loadSegment() {
        Segment segment = segmentSupplier.get();
        if (segment == null) {
            throw new SequenceException(
                    SequenceErrorCode.SEGMENT_LOAD_FAILED,
                    "Failed to load segment for " + seqName);
        }
        return segment;
    }

    // ---- getters / status ----

    /**
     * 获取当前使用中的 Segment。
     *
     * @return 当前 Segment
     */
    public Segment getCurrent() {
        return current;
    }

    /**
     * 获取备用 Segment。
     *
     * @return 备用 Segment，可能为 {@code null}
     */
    public Segment getStandby() {
        return standby;
    }

    /**
     * 获取当前和备用 Segment 的总剩余数量。
     *
     * @return 剩余可用数量
     */
    public long remaining() {
        return current.remaining() + standby.remaining();
    }

    /**
     * 获取活循环监控计数：stale preloadTriggered 重置次数。<p>
     * 用于监控异步预加载是否存在活循环风险。正常情况下此值应接近 0，
     * 如果持续增长说明线程池容量不足或预加载逻辑异常。
     * </p>
     *
     * @return stale reset 计数
     */
    public int getStaleResetCount() {
        return staleResetCount.get();
    }
}
