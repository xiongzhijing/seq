
package com.ccb.jx.seq.controller;

/**
 * 序列状态信息视图对象（VO）。
 * <p>
 * 用于 REST API 响应的序列状态数据传输对象，仅包含监控所需的关键字段，
 * 隔离内部模型与外部 API 契约。
 * </p>
 *
 * @author XZJ
 */
public class SequenceInfoVO {

    /** 序列名称 */
    private final String seqName;

    /** 序列模式（STRICT / CACHED） */
    private final String mode;

    /** 当前已分配的最大值 */
    private final long currentValue;

    /** 剩余可用数量（CACHED 模式下为双缓冲总剩余，STRICT 模式下为 0） */
    private final long remaining;

    /** 号段总容量（当前号段的 end - start + 1，STRICT 模式下为 0） */
    private final long totalCapacity;

    /**
     * 构造序列信息视图对象。
     *
     * @param seqName      序列名称
     * @param mode         序列模式
     * @param currentValue 当前已分配的最大值
     * @param remaining    剩余可用数量
     * @param totalCapacity 号段总容量
     */
    public SequenceInfoVO(String seqName, String mode, long currentValue,
                          long remaining, long totalCapacity) {
        this.seqName = seqName;
        this.mode = mode;
        this.currentValue = currentValue;
        this.remaining = remaining;
        this.totalCapacity = totalCapacity;
    }

    public String getSeqName() {
        return seqName;
    }

    public String getMode() {
        return mode;
    }

    public long getCurrentValue() {
        return currentValue;
    }

    public long getRemaining() {
        return remaining;
    }

    public long getTotalCapacity() {
        return totalCapacity;
    }
}
