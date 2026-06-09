
package com.ccb.jx.seq.buffer;

/**
 * 空号段（Null Object 模式）。
 * <p>
 * 表示尚未加载号段的状态，所有操作均返回"已耗尽"语义，
 * 用于替代 {@code null} 引用，消除 {@link DoubleBuffer} 中的 null 检查。
 * </p>
 *
 * <ul>
 *   <li>{@link #next()} — 返回 -1（号段耗尽）</li>
 *   <li>{@link #remaining()} — 返回 0</li>
 *   <li>{@link #isExhausted()} — 返回 true</li>
 *   <li>{@link #usage()} — 返回 1.0</li>
 *   <li>{@link #isInitialized()} — 返回 false</li>
 * </ul>
 *
 * @author XZJ
 */
public class EmptySegment extends Segment {

    /** 全局唯一实例 */
    public static final EmptySegment INSTANCE = new EmptySegment();

    private EmptySegment() {
        super(0, -1, true);
    }

    @Override
    public long next() {
        return -1;
    }

    @Override
    public long getCurrent() {
        return -1;
    }

    @Override
    public long remaining() {
        return 0;
    }

    @Override
    public long used() {
        return 0;
    }

    @Override
    public boolean isExhausted() {
        return true;
    }

    @Override
    public double usage() {
        return 1.0;
    }

    @Override
    public boolean isInitialized() {
        return false;
    }

    @Override
    public long getLoadTime() {
        return 0;
    }

    @Override
    public String toString() {
        return "EmptySegment";
    }
}
