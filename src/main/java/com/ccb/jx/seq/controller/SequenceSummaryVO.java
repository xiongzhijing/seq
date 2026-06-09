
package com.ccb.jx.seq.controller;

/**
 * 序列概要信息视图对象（VO）。
 * <p>
 * 用于 {@code GET /sequence/list} 响应的序列概要数据传输对象，
 * 仅包含监控列表所需的关键字段，隔离内部模型与外部 API 契约。
 * </p>
 *
 * @author XZJ
 */
public class SequenceSummaryVO {

    /** 序列名称 */
    private final String seqName;

    /** 序列模式（STRICT / CACHED） */
    private final String mode;

    /** 当前已分配的最大值 */
    private final long maxId;

    /** 号段步长 */
    private final int step;

    /** 描述信息 */
    private final String description;

    /**
     * 构造序列概要信息视图对象。
     *
     * @param seqName     序列名称
     * @param mode        序列模式
     * @param maxId       当前已分配的最大值
     * @param step        号段步长
     * @param description 描述信息
     */
    public SequenceSummaryVO(String seqName, String mode, long maxId,
                             int step, String description) {
        this.seqName = seqName;
        this.mode = mode;
        this.maxId = maxId;
        this.step = step;
        this.description = description;
    }

    public String getSeqName() {
        return seqName;
    }

    public String getMode() {
        return mode;
    }

    public long getMaxId() {
        return maxId;
    }

    public int getStep() {
        return step;
    }

    public String getDescription() {
        return description;
    }
}
