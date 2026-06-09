
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.dialect.NativeSequenceDialect;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原生 Sequence 严格连续模式生成器。
 * <p>
 * 委托数据库原生 Sequence 对象（如 TDSQL 的 {@code tdsql_nextval()}）生成连续值，
 * 消除应用层 {@code SELECT ... FOR UPDATE} 行锁竞争。
 * </p>
 *
 * <h3>两层懒创建</h3>
 * <ol>
 *   <li><b>Level 1</b> — {@code sequence_config} 行：由 {@link SequenceService#getConfig(String)}
 *       在配置不存在时自动 INSERT 默认配置（通过 {@code INSERT IGNORE} 保证并发安全）</li>
 *   <li><b>Level 2</b> — DB Sequence 对象：在本类 {@link #nextVal(String, SequenceConfig)} 中，
 *       当 {@code dialect.nextVal()} 抛出"Sequence 不存在"异常时，自动调用
 *       {@code dialect.createSequence()} 并重试</li>
 * </ol>
 * </p>
 *
 * <h3>max_id 更新策略</h3>
 * <p>best-effort：更新成功不影响正确性，失败仅打 WARN 日志，不阻塞返回值。</p>
 *
 * @author XZJ
 */
public class NativeStrictSequence implements SequenceStrategy {

    private static final Logger log = LoggerFactory.getLogger(NativeStrictSequence.class);

    private final NativeSequenceDialect dialect;
    private final SequenceRepository repository;

    /**
     * 构造原生 Sequence 生成器。
     *
     * @param dialect    原生 Sequence 方言实现，不能为 {@code null}
     * @param repository 序列数据访问层，不能为 {@code null}
     */
    public NativeStrictSequence(NativeSequenceDialect dialect,
                                SequenceRepository repository) {
        this.dialect = dialect;
        this.repository = repository;
    }

    @Override
    public long nextVal(String seqName, SequenceConfig config) {

        // 检查序列是否已耗尽（maxValue 边界检查）
        long currentMaxId = config.getMaxId();
        long maxValue = config.getMaxValue();
        if (currentMaxId >= maxValue && config.getCycle() != 1) {
            log.warn("[NATIVE] Sequence exhausted: seqName={}, maxId={}, maxValue={}, cycle=false",
                    seqName, currentMaxId, maxValue);
            throw new SequenceException(SequenceErrorCode.SEQ_EXHAUSTED,
                    String.format("maxValue=%d, cycle=false", maxValue));
        }

        // 委托数据库原生 Sequence 生成值
        long value;
        try {
            value = dialect.nextVal(config);
        } catch (SequenceException e) {
            // Level 2 懒创建自愈：DB Sequence 不存在时自动创建并重试
            if (dialect.isSequenceNotExist(e)) {
                log.warn("[NATIVE] Sequence object not found, attempting lazy creation: seqName={}", seqName);
                dialect.createSequence(config);
                // 重试 nextVal
                value = dialect.nextVal(config);
                log.info("[NATIVE] Lazy created and retrieved value: seqName={}, value={}", seqName, value);
            } else {
                throw e;
            }
        }

        // best-effort 更新 max_id（非关键路径，失败不影响返回值）
        try {
            repository.updateMaxId(seqName, value, currentMaxId);
        } catch (Exception e) {
            log.warn("[NATIVE] Failed to update max_id for native sequence: seqName={}, value={}, error={}",
                    seqName, value, e.getMessage());
        }

        return value;
    }
}
