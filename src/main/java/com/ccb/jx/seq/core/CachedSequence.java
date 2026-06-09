
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.buffer.EmptySegment;
import com.ccb.jx.seq.buffer.Segment;
import com.ccb.jx.seq.config.SequenceProperties;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.RecordStatus;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.model.SequenceRecord;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 号段缓存模式序列生成器（CACHED 模式）。
 * <p>
 * 使用双 Buffer 机制批量预取号段到内存，实现高性能序列值分配。
 * 每个序列对应一个 {@link DoubleBuffer}，存储在 {@link ConcurrentHashMap} 中，
 * 通过 {@link AtomicLong} 实现无锁分配。
 * </p>
 *
 * <h3>核心流程</h3>
 * <ol>
 *   <li>{@link #nextVal(String)} 从当前 Buffer 中获取下一个序列值，纯内存操作</li>
 *   <li>当前 Buffer 使用率达到 90% 时，触发异步预加载备用 Buffer</li>
 *   <li>当前 Buffer 耗尽时，切换到备用 Buffer（零等待）</li>
 *   <li>备用 Buffer 不可用时，同步加载新号段（降级）</li>
 * </ol>
 *
 * <h3>事务隔离</h3>
 * <ul>
 *   <li>{@code nextVal} 为纯内存操作，无需事务</li>
 *   <li>{@link #loadSegment(String)} 使用 {@code REQUIRES_NEW} 独立事务，与业务事务解耦</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>Buffer 管理：使用 {@link ConcurrentHashMap} 保证序列级安全</li>
 *   <li>值分配：{@link Segment#next()} 基于 {@code AtomicLong}，无锁</li>
 *   <li>Buffer 切换：{@link DoubleBuffer} 内部使用 {@code ReentrantLock} 保证互斥</li>
 *   <li>异步预加载：{@link SynchronousQueue} + 固定核心线程池，每个序列同一时刻只有一个加载任务</li>
 * </ul>
 *
 * @author XZJ
 */
public class CachedSequence implements SequenceStrategy {

    private static final Logger log = LoggerFactory.getLogger(CachedSequence.class);

    /** 异步线程池线程计数器 */
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    /** 每个序列的 DoubleBuffer 缓存 */
    private final ConcurrentHashMap<String, DoubleBuffer> bufferMap = new ConcurrentHashMap<>();

    /** 服务生命周期状态 */
    private volatile ServiceState state = ServiceState.RUNNING;

    /** 数据库操作 */
    private final SequenceRepository sequenceRepository;

    /** 编程式事务模板（解决自调用 @Transactional 失效问题） */
    private final TransactionTemplate transactionTemplate;

    /** 序列服务配置属性，用于获取实例 ID */
    private final SequenceProperties properties;

    /** 异步预加载线程池 */
    private final ExecutorService asyncExecutor;

    /** 最小步长，防止步长过小导致频繁 DB 访问 */
    private static final int MIN_STEP = 100;

    /** 最大步长，防止步长过大导致号段空洞过多 */
    private static final int MAX_STEP = 1_000_000;

    /** 目标号段消耗周期（分钟），用于动态步长计算 */
    private static final long SEGMENT_DURATION_MINUTES = 15;

    /**
     * 构造 CachedSequence 实例。
     *
     * @param sequenceRepository 数据库操作
     * @param transactionManager 事务管理器，用于创建编程式事务模板
     * @param properties         序列服务配置属性，用于获取实例 ID
     */
    public CachedSequence(SequenceRepository sequenceRepository,
                          PlatformTransactionManager transactionManager,
                          SequenceProperties properties) {
        this(sequenceRepository, transactionManager, 3, properties);
    }

    /**
     * 构造 CachedSequence 实例（可指定事务超时）。
     *
     * @param sequenceRepository         数据库操作
     * @param transactionManager         事务管理器，用于创建编程式事务模板
     * @param transactionTimeoutSeconds  事务超时秒数，默认 3
     * @param properties                 序列服务配置属性，用于获取实例 ID
     */
    public CachedSequence(SequenceRepository sequenceRepository,
                          PlatformTransactionManager transactionManager,
                          int transactionTimeoutSeconds,
                          SequenceProperties properties) {
        this.sequenceRepository = sequenceRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setTimeout(transactionTimeoutSeconds);
        this.properties = properties;
        this.asyncExecutor = new ThreadPoolExecutor(
                20, 100,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> new Thread(r, "sequence-async-preload-" + THREAD_COUNTER.getAndIncrement()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 实现 {@link SequenceStrategy} 接口的 nextVal 方法。
     * <p>
     * 委托给 {@link #nextVal(String)}，忽略 config 参数
     * （CACHED 模式从 DoubleBuffer 内存分配，不依赖 config 中的当前值）。
     * </p>
     *
     * @param seqName 序列名称
     * @param config  序列配置（CACHED 模式下不使用）
     * @return 下一个序列值
     */
    @Override
    public long nextVal(String seqName, SequenceConfig config) {
        return nextVal(seqName);
    }

    /**
     * 获取下一个序列值。
     * <p>
     * 优先从当前 Segment 分配（纯内存操作，无锁），
     * 当前 Segment 耗尽时自动切换到备用 Segment。
     * </p>
     *
     * @param seqName 序列名称
     * @return 下一个序列值
     * @throws SequenceException 如果序列服务正在关闭或号段加载失败
     */
    public long nextVal(String seqName) {
        checkShuttingDown();

        DoubleBuffer buffer = bufferMap.get(seqName);
        if (buffer == null) {
            // 首次访问，同步初始化 DoubleBuffer（会同步加载第一个 Segment）
            log.debug("[CACHED] Buffer not found, initializing: seqName={}, thread={}",
                    seqName, Thread.currentThread().getName());
            buffer = initBuffer(seqName);
        }

        try {
            return buffer.next();
        } catch (SequenceException e) {
            throw e;  // 保留原始错误码，透传给调用方
        } catch (Exception e) {
            throw new SequenceException(SequenceErrorCode.SEGMENT_LOAD_FAILED,
                    "Failed to get next value for sequence: " + seqName, e);
        }
    }

    /**
     * 检查关闭标志，服务关闭时拒绝新请求。
     *
     * @throws SequenceException 如果服务正在关闭
     */
    private void checkShuttingDown() {
        if (!state.acceptRequests()) {
            log.warn("[CACHED] Rejecting request, service state={}, thread={}",
                    state, Thread.currentThread().getName());
            throw new SequenceException(SequenceErrorCode.SHUTTING_DOWN,
                    "Sequence service is " + state + ", rejecting new request");
        }
    }

    /**
     * 初始化序列的 DoubleBuffer。
     * <p>
     * 使用 {@link ConcurrentHashMap#computeIfAbsent} 保证线程安全：
     * 同一序列只有一个线程执行初始化，其他线程等待。
     * </p>
     *
     * @param seqName 序列名称
     * @return 已初始化的 DoubleBuffer
     */
    private DoubleBuffer initBuffer(String seqName) {
        return bufferMap.computeIfAbsent(seqName, name -> {
            log.info("[CACHED] Initializing DoubleBuffer: seqName={}, thread={}",
                    name, Thread.currentThread().getName());
            DoubleBuffer buffer = new DoubleBuffer(name, () -> loadSegment(name));
            // 设置异步预加载提交器，将备用号段加载任务提交到线程池
            buffer.setAsyncTaskSubmitter(() -> asyncExecutor.submit(buffer::loadStandby));
            log.info("[CACHED] DoubleBuffer initialized: seqName={}, currentSegment={}",
                    name, buffer.getCurrent());
            return buffer;
        });
    }

    /**
     * 加载新号段（在独立事务中执行）。
     * <p>
     * 使用编程式事务 {@link TransactionTemplate}（{@code REQUIRES_NEW}），以：
     * <ul>
     *   <li>解耦于业务事务，sequence 操作不受业务回滚影响（Oracle 语义）</li>
     *   <li>保证 {@code max_id} 更新立即提交，其他实例可见</li>
     * </ul>
     * </p>
     * <p>
     * 注意：此处使用编程式事务而非 {@code @Transactional}，因为此方法通过
     * 内部 lambda 被 {@link DoubleBuffer} 调用（自调用场景），Spring AOP 代理
     * 不会拦截自调用，导致 {@code @Transactional} 失效。
     * </p>
     *
     * @param seqName 序列名称
     * @return 新加载的号段
     * @throws SequenceException 如果序列不存在或数据库操作失败
     */
    public Segment loadSegment(String seqName) {
        return transactionTemplate.execute(status -> {
            log.debug("[CACHED] loadSegment enter: seqName={}, thread={}",
                    seqName, Thread.currentThread().getName());

            // Step 1: 查询序列配置（带行锁，保证号段加载原子性）
            // 使用 SELECT ... FOR UPDATE 锁定行，防止并发实例在读取 max_id 和更新之间插入
            SequenceConfig config = sequenceRepository.selectConfigForUpdate(seqName);
            if (config == null) {
                log.error("[CACHED] Sequence not found during loadSegment: seqName={}", seqName);
                throw new SequenceException(SequenceErrorCode.SEQ_NOT_FOUND, seqName);
            }
            log.debug("[CACHED] FOR UPDATE acquired for loadSegment: seqName={}, maxId={}, step={}, incrementBy={}",
                    seqName, config.getMaxId(), config.getStep(), config.getIncrementBy());

            // Step 2: 计算动态步长（非首次加载时）
            int step = config.getStep();
            DoubleBuffer buffer = bufferMap.get(seqName);
            if (buffer != null && !(buffer.getCurrent() instanceof EmptySegment)) {
                Segment currentSegment = buffer.getCurrent();
                long actualDuration = System.currentTimeMillis() - currentSegment.getLoadTime();
                int oldStep = step;
                step = calculateDynamicStep(step, actualDuration);
                if (step != oldStep) {
                    log.info("[CACHED] Dynamic step adjusted: seqName={}, oldStep={}, newStep={}, actualDuration={}ms",
                            seqName, oldStep, step, actualDuration);
                }
            } else {
                log.debug("[CACHED] Using configured step (no current segment): seqName={}, step={}", seqName, step);
            }

            // Step 3: 基于行锁保护，读取当前 max_id 并计算新值
            // 在 FOR UPDATE 锁保护下，先读取 oldMaxId，再通过 CAS 更新为 newMaxId
            // 锁保证了读取和更新之间不会有其他实例插入，CAS 提供额外安全网
            long oldMaxId = config.getMaxId();
            long newMaxId = oldMaxId + step;

            // CYCLE 和 maxValue 边界检查
            long maxValue = config.getMaxValue();
            long minValue = config.getMinValue();
            boolean cycle = config.getCycle() == 1;

            if (newMaxId > maxValue) {
                if (cycle) {
                    // CYCLE 回绕：从 minValue 重新开始
                    newMaxId = minValue + step - 1;
                    log.info("[CACHED] CYCLE wrap-around for '{}': oldMaxId={}, newMaxId={}, minValue={}",
                            seqName, oldMaxId, newMaxId, minValue);
                } else {
                    log.error("[CACHED] Sequence exhausted for '{}': newMaxId={} > maxValue={}",
                            seqName, newMaxId, maxValue);
                    throw new SequenceException(SequenceErrorCode.SEQ_EXHAUSTED,
                            "Sequence '" + seqName + "' exhausted: newMaxId=" + newMaxId + " > maxValue=" + maxValue);
                }
            }

            log.debug("[CACHED] Updating maxId: seqName={}, oldMaxId={}, newMaxId={}, step={}",
                    seqName, oldMaxId, newMaxId, step);

            int updated = sequenceRepository.updateMaxId(seqName, newMaxId, oldMaxId);
            if (updated == 0) {
                log.error("[CACHED] updateMaxId affected 0 rows (CAS conflict): seqName={}, oldMaxId={}, newMaxId={}, step={}",
                        seqName, oldMaxId, newMaxId, step);
                throw new SequenceException(SequenceErrorCode.DB_ERROR,
                        "Failed to update max_id for sequence: " + seqName
                                + ", oldMaxId=" + oldMaxId + ", newMaxId=" + newMaxId
                                + ", step=" + step + ", affected rows=0");
            }

            long startValue = newMaxId - step + 1;

            // Step 4: 插入号段分配记录（审计追踪）
            SequenceRecord record = new SequenceRecord();
            record.setSeqName(seqName);
            record.setStartValue(startValue);
            record.setEndValue(newMaxId);
            record.setStatus(RecordStatus.ALLOCATED);
            record.setAllocTime(new Date());
            record.setInstanceId(properties.getInstanceId());
            sequenceRepository.insertRecord(record);

            log.info("[CACHED] Segment loaded: seqName={}, range=[{}, {}], step={}, thread={}",
                    seqName, startValue, newMaxId, step, Thread.currentThread().getName());

            return new Segment(startValue, newMaxId);
        });
    }

    /**
     * 计算动态步长。
     * <p>
     * 采用 Leaf 风格的线性调整算法：
     * <pre>
     *   ratio = SEGMENT_DURATION / actualDuration
     *   nextStep = round(currentStep * ratio)
     * </pre>
     * </p>
     *
     * <h3>设计目标</h3>
     * <ul>
     *   <li>DB 更新周期稳定在 ~15 分钟（{@link #SEGMENT_DURATION_MINUTES}）</li>
     *   <li>消费快则步长增大（减少 DB 访问频率）</li>
     *   <li>消费慢则步长减小（减少号段空洞浪费）</li>
     *   <li>步长范围限制在 [{@link #MIN_STEP}, {@link #MAX_STEP}] 之间</li>
     * </ul>
     *
     * @param currentStep      当前步长
     * @param actualDurationMs 实际消耗时长（毫秒）
     * @return 调整后的步长
     */
    private int calculateDynamicStep(int currentStep, long actualDurationMs) {
        // 使用浮点数计算保留精度，避免 toMinutes 截断导致步长调整不准
        double actualDurationMinutes = actualDurationMs / (60.0 * 1000.0);
        if (actualDurationMinutes < 1.0) {
            actualDurationMinutes = 1.0;
        }

        // 线性比例调整
        double ratio = SEGMENT_DURATION_MINUTES / actualDurationMinutes;
        long targetStep = Math.round(currentStep * ratio);

        // 阻尼：单次调整不超过 2 倍，防止步长振荡
        long maxStep = (long) (currentStep * 2);
        long minStep = (long) Math.ceil(currentStep / 2.0);
        targetStep = Math.max(minStep, Math.min(maxStep, targetStep));

        // 限制在全局范围内
        targetStep = Math.max(targetStep, MIN_STEP);
        targetStep = Math.min(targetStep, MAX_STEP);

        if (log.isDebugEnabled()) {
            log.debug("Dynamic step calculated: seqStep=[{}->{}], actualDuration={}min, ratio={}, damped=true",
                    currentStep, targetStep, String.format("%.1f", actualDurationMinutes), String.format("%.2f", ratio));
        }

        // MAX_STEP = 1_000_000 在 int 范围内，显式 clamp 防御未来常量变更导致的溢出
        return (int) Math.min(targetStep, Integer.MAX_VALUE);
    }

    /**
     * 持久化未使用号段（优雅关闭时调用）。
     * <p>
     * 在 Spring 上下文关闭事件触发时，遍历所有已加载的 DoubleBuffer，
     * 将当前未消耗完的号段信息记录到日志，便于后续审计和恢复。
     * </p>
     *
     * <h3>关机流程</h3>
     * <ol>
     *   <li>设置 {@link #shuttingDown} 标志为 true，拒绝新请求</li>
     *   <li>调用此方法记录未使用号段</li>
     *   <li>等待异步任务完成</li>
     *   <li>关闭线程池</li>
     * </ol>
     */
    public void persistUnusedSegments() {
        log.info("Persisting unused segments for graceful shutdown, total sequences: {}",
                bufferMap.size());
        for (Map.Entry<String, DoubleBuffer> entry : bufferMap.entrySet()) {
            String seqName = entry.getKey();
            DoubleBuffer buffer = entry.getValue();
            Segment current = buffer.getCurrent();
            if (!(current instanceof EmptySegment) && !current.isExhausted()) {
                long remaining = current.remaining();
                if (remaining > 0) {
                    log.warn("Unused segment for sequence [{}]: current={}, end={}, remaining={}",
                            seqName, current.getCurrent(), current.getEnd(), remaining);
                }
            }
        }
    }

    /**
     * 状态转换。
     * <p>
     * 将服务状态转换到目标状态，转换必须合法（{@link ServiceState#canTransitionTo}）。
     * </p>
     *
     * @param target 目标状态
     * @throws IllegalStateException 如果转换不合法
     */
    public void transitionTo(ServiceState target) {
        ServiceState current = this.state;
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition from " + current + " to " + target);
        }
        this.state = target;
        log.info("[CACHED] State transition: {} -> {}", current, target);
    }

    /**
     * 设置关闭标志（向后兼容）。
     * <p>
     * 建议使用 {@link #transitionTo(ServiceState)} 替代。
     * </p>
     *
     * @param shuttingDown 是否关闭
     * @deprecated 使用 {@link #transitionTo(ServiceState)} 替代
     */
    @Deprecated
    public void setShuttingDown(boolean shuttingDown) {
        if (shuttingDown) {
            transitionTo(ServiceState.SHUTTING_DOWN);
        }
    }

    /**
     * 获取当前服务状态。
     *
     * @return 当前状态
     */
    public ServiceState getState() {
        return state;
    }

    /**
     * 获取指定序列的 DoubleBuffer 状态。
     *
     * @param seqName 序列名称
     * @return DoubleBuffer，如果未加载则返回 {@code null}
     */
    public DoubleBuffer getBuffer(String seqName) {
        return bufferMap.get(seqName);
    }

    /**
     * 获取所有已加载的序列名称集合。
     *
     * @return 已加载序列名称的 KeySet 视图
     */
    public ConcurrentHashMap.KeySetView<String, DoubleBuffer> getLoadedSequenceNames() {
        return bufferMap.keySet();
    }

    /**
     * 移除指定序列的 DoubleBuffer。
     * <p>
     * 当序列从配置表中删除后调用此方法，释放内存中的号段缓存。
     * 调用后下次 {@link #nextVal(String)} 将尝试重新加载号段，
     * 若序列已不在配置表中则抛出 {@code SEQ_NOT_FOUND}。
     * </p>
     *
     * @param seqName 序列名称
     */
    public void removeBuffer(String seqName) {
        DoubleBuffer removed = bufferMap.remove(seqName);
        if (removed != null) {
            log.info("[CACHED] Removed stale buffer for '{}'", seqName);
        }
    }

    /**
     * 清理不在活跃序列集合中的过期 DoubleBuffer。
     * <p>
     * 由配置缓存刷新调度器周期性调用，释放已从配置表中删除的序列的号段缓存，
     * 防止长期运行后 {@link #bufferMap} 无限增长。
     * </p>
     *
     * @param activeSeqNames 当前配置表中存在的序列名称集合
     * @return 清理的条目数
     */
    public int evictStaleBuffers(Set<String> activeSeqNames) {
        int totalBuffers = bufferMap.size();
        log.debug("[CACHED] evictStaleBuffers: scanning {} buffer entries against {} active configs",
                totalBuffers, activeSeqNames.size());
        int removed = 0;
        for (String name : new HashSet<>(bufferMap.keySet())) {
            if (!activeSeqNames.contains(name)) {
                log.info("[CACHED] evictStaleBuffers: sequence '{}' not in active configs, removing buffer", name);
                removeBuffer(name);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[CACHED] Evicted {}/{} stale DoubleBuffer entries, remaining: {}",
                    removed, totalBuffers, bufferMap.size());
        } else {
            log.debug("[CACHED] evictStaleBuffers: no stale buffers found, all {} entries are active", totalBuffers);
        }
        return removed;
    }

    /**
     * 获取异步预加载线程池。
     *
     * @return 异步执行器
     */
    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    /**
     * 获取当前活跃号段数量。
     * <p>
     * 统计所有已加载序列中未耗尽的当前号段数量，
     * 用于监控和审计。
     * </p>
     *
     * @return 活跃号段数量
     */
    public int getActiveSegmentCount() {
        int count = 0;
        for (Map.Entry<String, DoubleBuffer> entry : bufferMap.entrySet()) {
            Segment current = entry.getValue().getCurrent();
            if (current != null && !current.isExhausted()) {
                count++;
            }
        }
        return count;
    }

}
