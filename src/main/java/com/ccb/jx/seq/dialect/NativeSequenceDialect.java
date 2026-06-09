
package com.ccb.jx.seq.dialect;

import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.SequenceConfig;

/**
 * 原生 Sequence 方言接口。
 * <p>
 * 抽象不同数据库（TDSQL、MariaDB、MySQL 8.0+）原生 Sequence 的 SQL 差异，
 * 提供统一的 {@code nextVal}、{@code createSequence}、{@code dropSequence}、
 * {@code sequenceExists}、{@code isSequenceNotExist} 操作。
 * </p>
 *
 * @author XZJ
 */
public interface NativeSequenceDialect {

    /**
     * 生成下一个原生 Sequence 值。
     *
     * @param config 序列配置（含 seqName、startWith、minValue、maxValue、incrementBy、cycle 等）
     * @return 下一个序列值
     */
    long nextVal(SequenceConfig config);

    /**
     * 在数据库中创建对应的原生 Sequence 对象。
     *
     * @param config 序列配置
     */
    void createSequence(SequenceConfig config);

    /**
     * 删除数据库中的原生 Sequence 对象。
     *
     * @param seqName 序列名称
     */
    void dropSequence(String seqName);

    /**
     * 检测数据库中原生 Sequence 对象是否存在。
     *
     * @param seqName 序列名称
     * @return {@code true} 如果存在
     */
    boolean sequenceExists(String seqName);

    /**
     * 判断异常是否为"Sequence 不存在"。
     * <p>
     * 用于 {@link NativeStrictSequence} 的懒创建自愈机制：
     * 当 {@link #nextVal(SequenceConfig)} 抛出此异常时，自动创建 Sequence 并重试。
     * </p>
     *
     * @param e 序列异常
     * @return {@code true} 如果异常原因为 Sequence 不存在
     */
    boolean isSequenceNotExist(SequenceException e);
}
