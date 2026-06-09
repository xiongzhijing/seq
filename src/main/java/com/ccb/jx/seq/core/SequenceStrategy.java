
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.model.SequenceConfig;

/**
 * 序列生成策略接口（统一 STRICT / CACHED 模式）。
 * <p>
 * 定义 {@code nextVal} 方法，用于生成下一个序列值。
 * 通过 {@link SequenceService} 的策略映射（{@code Map<Mode, SequenceStrategy>}）
 * 根据 {@code mode} 字段路由到具体实现：
 * <ul>
 *   <li>{@link StrictSequence} — 使用 {@code SELECT ... FOR UPDATE} 悲观锁</li>
 *   <li>{@link NativeStrictSequence} — 委托数据库原生 Sequence 生成</li>
 *   <li>{@link CachedSequence} — 号段缓存 + 双缓冲</li>
 * </ul>
 * </p>
 *
 * @author XZJ
 */
@FunctionalInterface
public interface SequenceStrategy {

    /**
     * 生成下一个序列值。
     *
     * @param seqName 序列名称，由 {@link SequenceService#nextVal(String)} 传入
     * @param config  序列配置，由 {@link SequenceService#getConfig(String)} 加载
     * @return 下一个序列值
     */
    long nextVal(String seqName, SequenceConfig config);
}
