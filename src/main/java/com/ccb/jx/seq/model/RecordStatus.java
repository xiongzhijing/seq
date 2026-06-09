
package com.ccb.jx.seq.model;

/**
 * 号段记录状态枚举。
 * <p>
 * 对应 {@code sequence_record} 表的 {@code status} 列，标识号段的分配与生命周期状态。
 *
 * @author XZJ
 */
public enum RecordStatus {

    /** 已分配，正在使用 */
    ALLOCATED,

    /** 已过期/耗尽 */
    EXPIRED,

    /** 已回收 */
    RECYCLED
}
