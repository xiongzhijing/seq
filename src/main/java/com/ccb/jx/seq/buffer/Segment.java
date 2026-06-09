
package com.ccb.jx.seq.buffer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段模型，使用 AtomicLong 实现无锁分配。
 * <p>
 * 一个 Segment 表示一个连续的序列值范围 [start, end]（闭区间），
 * 内部通过 {@link AtomicLong} 实现线程安全的并发分配，无需加锁。
 * 适用于 CACHED 模式下的号段缓存场景。
 *
 * @author XZJ
 */
public class Segment {

    /** 号段起始值（包含） */
    private final long start;

    /** 号段结束值（包含） */
    private final long end;

    /** 当前分配位置，原子递增 */
    private final AtomicLong current;

    /** 是否已初始化 */
    private volatile boolean initialized;

    /** 号段加载时间戳 */
    private volatile long loadTime;

    /**
     * 使用指定的范围构造号段。
     *
     * @param start 起始值（包含），必须 {@code <= end}
     * @param end   结束值（包含），必须 {@code >= start}
     */
    public Segment(long start, long end) {
        if (start > end) {
            throw new IllegalArgumentException(
                    "start (" + start + ") must be <= end (" + end + ")");
        }
        this.start = start;
        this.end = end;
        this.current = new AtomicLong(start);
        this.initialized = true;
        this.loadTime = System.currentTimeMillis();
    }

    /**
     * 子类扩展构造器（跳过参数校验）。
     * <p>
     * 供 {@link EmptySegment} 等特殊子类使用，跳过 {@code start <= end} 校验
     * 和 {@code AtomicLong} 初始化。
     * </p>
     *
     * @param start           起始值
     * @param end             结束值
     * @param skipValidation  是否跳过参数校验和初始化
     */
    protected Segment(long start, long end, boolean skipValidation) {
        this.start = start;
        this.end = end;
        if (!skipValidation) {
            if (start > end) {
                throw new IllegalArgumentException(
                        "start (" + start + ") must be <= end (" + end + ")");
            }
            this.current = new AtomicLong(start);
            this.initialized = true;
            this.loadTime = System.currentTimeMillis();
        } else {
            this.current = null;
            this.initialized = false;
            this.loadTime = 0;
        }
    }

    /**
     * 获取下一个序列值，原子递增。
     * <p>
     * 使用 {@code getAndIncrement} 原子递增，然后检查是否超出范围。
     * 超出范围时返回 -1，但 {@code current} 会被推到 {@code end + 1}，
     * 后续调用直接返回 -1。这种方式在高并发下可能将 current 推到
     * {@code end + 1} 以上，但不影响正确性——超出 end 的值不会被返回。
     * </p>
     *
     * @return 下一个序列值；如果号段已耗尽则返回 -1
     */
    public long next() {
        long value = current.getAndIncrement();
        if (value > end) {
            return -1;
        }
        return value;
    }

    /**
     * 获取当前分配位置（不递增）。
     *
     * @return 当前分配位置
     */
    public long getCurrent() {
        return current.get();
    }

    /**
     * 计算剩余可用数量。
     *
     * @return 剩余可用数量；如果已耗尽则返回 0
     */
    public long remaining() {
        long cur = current.get();
        if (cur > end) {
            return 0;
        }
        return end - cur + 1;
    }

    /**
     * 计算已使用数量（相对于 start）。
     *
     * @return 已使用数量
     */
    public long used() {
        return current.get() - start;
    }

    /**
     * 判断号段是否已耗尽。
     *
     * @return 如果已耗尽返回 {@code true}
     */
    public boolean isExhausted() {
        return current.get() > end;
    }

    /**
     * 计算使用率（0.0 ~ 1.0）。
     *
     * @return 使用率，1.0 表示已耗尽
     */
    public double usage() {
        long total = end - start + 1;
        if (total <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) (current.get() - start) / total);
    }

    // ---- getters ----

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public long getLoadTime() {
        return loadTime;
    }

    @Override
    public String toString() {
        return "Segment{start=" + start + ", end=" + end
                + ", current=" + current.get()
                + ", remaining=" + remaining() + "}";
    }
}
