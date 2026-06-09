
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 严格连续模式序列生成器。
 * <p>
 * 使用 {@code SELECT ... FOR UPDATE} 行级悲观锁实现多实例串行化分配，
 * 保证每次 nextVal 返回的值严格连续、不跳号。
 * <p>
 * <b>事务边界</b>：每次 {@code nextVal} 调用在独立事务中执行
 * （{@link TransactionTemplate} + {@code REQUIRES_NEW}），与业务事务完全解耦。
 * 序列值在业务事务回滚时不会回收（兼容 Oracle 语义）。
 * <p>
 * <b>Oracle 兼容语义</b>：支持 {@code INCREMENT BY}、{@code MINVALUE}、
 * {@code MAXVALUE}、{@code CYCLE}、{@code START WITH} 等标准 Oracle 序列特性。
 * <p>
 * <b>并发策略</b>：
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE} 获取行级悲观锁，阻塞其他竞争实例</li>
 *   <li>直接更新 {@code max_id}，FOR UPDATE 已保证串行化，无需 CAS 重试</li>
 * </ol>
 * <p>
 * <b>性能</b>：受限于行锁竞争，单实例约 50 TPS，4 实例约 100-200 TPS。
 * <p>
 * {@code native-sequence-dialect=NONE}（默认）时，由 {@link SequenceAutoConfiguration}
 * 装配此实现。
 *
 * @author XZJ
 * @see SequenceStrategy
 * @see NativeStrictSequence
 */
public class StrictSequence implements SequenceStrategy {

    private static final Logger log = LoggerFactory.getLogger(StrictSequence.class);

    private final SequenceRepository sequenceRepository;

    /** 编程式事务模板（解决自调用 @Transactional 失效问题） */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造严格连续模式序列生成器。
     *
     * @param sequenceRepository 序列数据访问层，不能为 {@code null}
     * @param transactionManager 事务管理器，用于创建编程式事务模板
     */
    public StrictSequence(SequenceRepository sequenceRepository,
                           PlatformTransactionManager transactionManager) {
        this(sequenceRepository, transactionManager, 3);
    }

    /**
     * 构造严格连续模式序列生成器（可指定事务超时）。
     *
     * @param sequenceRepository 序列数据访问层，不能为 {@code null}
     * @param transactionManager 事务管理器，用于创建编程式事务模板
     * @param transactionTimeoutSeconds 事务超时秒数，默认 3
     */
    public StrictSequence(SequenceRepository sequenceRepository,
                           PlatformTransactionManager transactionManager,
                           int transactionTimeoutSeconds) {
        this.sequenceRepository = sequenceRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setTimeout(transactionTimeoutSeconds);
    }

    /**
     * 获取下一个严格连续的序列值。
     * <p>
     * 实现 {@link SequenceStrategy} 接口，在独立事务中执行
     * {@code SELECT ... FOR UPDATE} 锁定行记录，计算下一个值并更新 {@code max_id}。
     *
     * @param seqName 序列名称
     * @param config  序列配置（含 incrementBy、minValue、maxValue、cycle、startWith 等）
     * @return 下一个序列值
     * @throws SequenceException 若序列不存在、已耗尽或更新失败
     */
    @Override
    public long nextVal(String seqName, SequenceConfig config) {
        return nextValInternal(seqName, config);
    }

    /**
     * 内部实现：获取下一个严格连续的序列值。
     * <p>
     * 在独立事务中执行 {@code SELECT ... FOR UPDATE} 锁定行记录，
     * 计算下一个值并更新 {@code max_id}。
     * FOR UPDATE 悲观锁已保证串行化，无需 CAS 重试。
     * <p>
     * 当序列达到 {@code max_value} 且未配置 {@code CYCLE} 时，
     * 抛出 {@link SequenceException#SEQ_EXHAUSTED}。
     * 当配置 {@code CYCLE} 时，自动回绕到 {@code min_value}，
     * 并将 {@code max_id} 设为 {@code min_value - incrementBy}，
     * 使得下次调用返回 {@code min_value}。
     * <p>
     * 当 {@code max_id} 为 DDL 默认值且 {@code start_with} 配置了不同起始值时，
     * 自动修正 {@code currentMaxId} 使首次 {@code nextVal} 返回 {@code start_with}。
     *
     * @param seqName 序列名称，不能为 {@code null} 或空字符串
     * @param config  序列配置（可能被 FOR UPDATE 重新读取覆盖）
     * @return 下一个序列值
     * @throws SequenceException 若序列不存在、已耗尽或更新失败
     */
    private long nextValInternal(String seqName, SequenceConfig config) {
        return transactionTemplate.execute(status -> {
            log.debug("[STRICT] nextVal enter: seqName={}, thread={}", seqName, Thread.currentThread().getName());

            // 1. SELECT ... FOR UPDATE 锁定行，阻塞其他竞争事务
            SequenceConfig lockedConfig = sequenceRepository.selectConfigForUpdate(seqName);
            if (lockedConfig == null) {
                log.warn("[STRICT] Sequence not found: seqName={}", seqName);
                throw new SequenceException(SequenceErrorCode.SEQ_NOT_FOUND,
                        "seqName=" + seqName);
            }
            log.debug("[STRICT] FOR UPDATE acquired: seqName={}, maxId={}, incrementBy={}, minValue={}, maxValue={}, cycle={}, startWith={}",
                    seqName, lockedConfig.getMaxId(), lockedConfig.getIncrementBy(), lockedConfig.getMinValue(),
                    lockedConfig.getMaxValue(), lockedConfig.getCycle(), lockedConfig.getStartWith());

            // 2. 读取当前配置
            long currentMaxId = lockedConfig.getMaxId();
            long originalMaxId = currentMaxId;
            int incrementBy = lockedConfig.getIncrementBy();
            long minValue = lockedConfig.getMinValue();
            long maxValue = lockedConfig.getMaxValue();
            boolean cycle = lockedConfig.getCycle() == 1;
            long startWith = lockedConfig.getStartWith();

            // 3. start_with 初始化：仅当 max_id 为 DDL 默认值（1）且 start_with 配置不同时修正
            //    修正后 nextVal = (startWith - incrementBy) + incrementBy = startWith
            if (currentMaxId == 1L && startWith > 1) {
                currentMaxId = startWith - incrementBy;
                log.info("[STRICT] start_with correction: seqName={}, originalMaxId={}, correctedMaxId={}, startWith={}",
                        seqName, originalMaxId, currentMaxId, startWith);
            }

            // 4. 计算下一个值及需要持久化的新 max_id
            long nextVal;
            long newMaxId;
            long candidate = currentMaxId + (long) incrementBy;

            if (candidate > maxValue) {
                if (cycle) {
                    log.info("[STRICT] CYCLE wrap: seqName={}, candidate={}, maxValue={}, cycling to minValue={}",
                            seqName, candidate, maxValue, minValue);
                    nextVal = minValue;
                    // CYCLE 回绕：将 max_id 设为 minValue，
                    // 使得下次 nextVal = minValue + incrementBy（Oracle CYCLE 语义）。
                    // 注意：minValue 本身由本次调用返回，maxId 记录的是"已分配的最大值"，
                    // 因此 newMaxId = minValue 而非 minValue - incrementBy。
                    newMaxId = minValue;
                } else {
                    log.warn("[STRICT] Sequence exhausted: seqName={}, candidate={}, maxValue={}, cycle=false",
                            seqName, candidate, maxValue);
                    throw new SequenceException(SequenceErrorCode.SEQ_EXHAUSTED,
                            String.format("maxValue=%d, cycle=false", maxValue));
                }
            } else {
                nextVal = candidate;
                newMaxId = nextVal;
                log.debug("[STRICT] Normal allocation: seqName={}, nextVal={}, newMaxId={}", seqName, nextVal, newMaxId);
            }

            // 5. 更新 max_id（FOR UPDATE 已保证串行化，无需 CAS 重试）
            int updated = sequenceRepository.updateMaxId(seqName, newMaxId, originalMaxId);
            if (updated == 0) {
                log.error("[STRICT] updateMaxId affected 0 rows: seqName={}, newMaxId={}, originalMaxId={}",
                        seqName, newMaxId, originalMaxId);
                throw new SequenceException(SequenceErrorCode.DB_ERROR,
                        "Failed to update max_id for sequence: " + seqName);
            }

            log.debug("[STRICT] nextVal exit: seqName={}, value={}, originalMaxId={}, newMaxId={}, thread={}",
                    seqName, nextVal, originalMaxId, newMaxId, Thread.currentThread().getName());
            return nextVal;
        });
    }
}
