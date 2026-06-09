
package com.ccb.jx.seq.model;

import java.util.Date;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 号段分配记录实体，对应数据库 {@code sequence_record} 表。
 * <p>
 * 记录每个已分配的号段范围及其状态，用于审计追踪和崩溃恢复。
 *
 * @author XZJ
 */
public class SequenceRecord {

    /** 主键 ID（自增） */
    private Long id;

    /** 序列名称 */
    private String seqName;

    /** 号段起始值（包含） */
    private Long startValue;

    /** 号段结束值（包含） */
    private Long endValue;

    /** 分配该号段的实例标识（ip:port） */
    private String instanceId;

    /**
     * 号段状态。
     * <ul>
     *   <li>{@code ALLOCATED} — 已分配，正在使用</li>
     *   <li>{@code EXPIRED} — 已过期/耗尽</li>
     *   <li>{@code RECYCLED} — 已回收</li>
     * </ul>
     */
    private RecordStatus status;

    /** 分配时间 */
    private Date allocTime;

    /** 过期时间 */
    private Date expireTime;

    /** 无参构造器 */
    public SequenceRecord() {
    }

    // ====== getter / setter ======

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSeqName() {
        return seqName;
    }

    public void setSeqName(String seqName) {
        this.seqName = seqName;
    }

    public Long getStartValue() {
        return startValue;
    }

    public void setStartValue(Long startValue) {
        this.startValue = startValue;
    }

    public Long getEndValue() {
        return endValue;
    }

    public void setEndValue(Long endValue) {
        this.endValue = endValue;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public void setStatus(RecordStatus status) {
        this.status = status;
    }

    public Date getAllocTime() {
        return allocTime;
    }

    public void setAllocTime(Date allocTime) {
        this.allocTime = allocTime;
    }

    public Date getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(Date expireTime) {
        this.expireTime = expireTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SequenceRecord)) {
            return false;
        }
        SequenceRecord that = (SequenceRecord) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", SequenceRecord.class.getSimpleName() + "[", "]")
                .add("id=" + id)
                .add("seqName='" + seqName + "'")
                .add("startValue=" + startValue)
                .add("endValue=" + endValue)
                .add("instanceId='" + instanceId + "'")
                .add("status='" + status + "'")
                .add("allocTime=" + allocTime)
                .add("expireTime=" + expireTime)
                .toString();
    }
}
