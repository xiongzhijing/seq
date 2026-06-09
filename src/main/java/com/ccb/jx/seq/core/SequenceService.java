
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;
import com.ccb.jx.seq.session.SessionHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 序列服务统一入口。
 * <p>
 * 根据序列的 {@code mode} 配置通过策略映射路由到对应的 {@link SequenceStrategy} 实现。
 * 提供Oracle 兼容的 {@code nextVal} / {@code currVal} 接口。
 * </p>
 *
 * <h3>路由规则</h3>
 * <ul>
 *   <li>{@link Mode#STRICT} → {@link SequenceStrategy#nextVal(String, SequenceConfig)} —
 *       每次调用走数据库（FOR UPDATE 或原生 Sequence），保证严格连续</li>
 *   <li>{@link Mode#CACHED} → {@link CachedSequence#nextVal(String, SequenceConfig)} —
 *       从内存双缓冲分配，高吞吐量，崩溃时有空洞风险</li>
 * </ul>
 *
 * <h3>sequence_config 懒创建（Level 1）</h3>
 * <p>当 {@link #getConfig(String)} 中 {@code sequence_config} 表不存在对应行时，
 * 自动按默认参数 INSERT 一条新记录（使用 INSERT IGNORE 保证并发安全），
 * 使得调用方无需预创建即可首次调用 {@link #nextVal(String)}。</p>
 *
 * <h3>会话语义</h3>
 * <p>{@link #currVal(String)} 基于 {@link ThreadLocal} 实现，
 * 在一次 HTTP 请求（线程）的生命周期内记录最后一次 {@link #nextVal(String)} 的返回值，
 * 兼容 Oracle {@code CURRVAL} 语义。</p>
 *
 * <h3>配置缓存</h3>
 * <p>序列配置通过 {@link ConcurrentHashMap} 缓存，首次访问时懒加载，
 * 通过 {@link #evictConfig(String)} 或 {@link #clearCache()} 手动失效。
 * 同时通过 {@link ScheduledExecutorService} 定时清空缓存（默认每 5 分钟），
 * 确保 DBA 在数据库中修改配置后，应用能在 TTL 时间内感知变更。
 * 定时刷新逻辑由 {@link ConfigCacheRefresher} 实现，遵循单一职责原则。
 * </p>
 *
 * @author XZJ
 */
public class SequenceService {

    private static final Logger log = LoggerFactory.getLogger(SequenceService.class);

    /** 策略映射（Mode → SequenceStrategy），替代 if-else 路由 */
    private final Map<Mode, SequenceStrategy> strategyMap;

    /** 序列数据访问层 */
    private final SequenceRepository sequenceRepository;

    /** 序列配置缓存（序列名称 → SequenceConfig） */
    private final ConcurrentMap<String, SequenceConfig> configCache = new ConcurrentHashMap<>();

    /** 配置缓存定时刷新调度器（daemon 线程，不阻止 JVM 退出） */
    private final ScheduledExecutorService cacheRefreshScheduler;

    /** 配置缓存 TTL（分钟），超过此时间自动清空缓存以感知 DB 配置变更 */
    private final long configCacheTtlMinutes;

    /**
     * 构造序列服务统一入口。
     * <p>
     * 使用默认配置缓存 TTL（5 分钟）。
     *
     * @param strategyMap        策略映射（Mode → SequenceStrategy），不能为 {@code null}
     * @param sequenceRepository 数据访问层，不能为 {@code null}
     * @throws NullPointerException 如果任一参数为 {@code null}
     */
    public SequenceService(Map<Mode, SequenceStrategy> strategyMap,
                           SequenceRepository sequenceRepository) {
        this(strategyMap, sequenceRepository, 5L);
    }

    /**
     * 构造序列服务统一入口。
     *
     * @param strategyMap           策略映射（Mode → SequenceStrategy），不能为 {@code null}
     * @param sequenceRepository    数据访问层，不能为 {@code null}
     * @param configCacheTtlMinutes 配置缓存 TTL（分钟），必须为正数。
     *                              每隔此时间自动清空缓存，下次访问时从数据库重新加载，
     *                              确保 DBA 修改配置后能在 TTL 时间内生效
     * @throws NullPointerException     如果前两个参数任一为 {@code null}
     * @throws IllegalArgumentException 如果 {@code configCacheTtlMinutes} 非正数
     */
    public SequenceService(Map<Mode, SequenceStrategy> strategyMap,
                           SequenceRepository sequenceRepository,
                           long configCacheTtlMinutes) {
        this.strategyMap = Collections.unmodifiableMap(
                Objects.requireNonNull(strategyMap, "strategyMap must not be null"));
        this.sequenceRepository = Objects.requireNonNull(sequenceRepository, "sequenceRepository must not be null");
        if (configCacheTtlMinutes <= 0) {
            throw new IllegalArgumentException(
                    "configCacheTtlMinutes must be positive, got: " + configCacheTtlMinutes);
        }
        this.configCacheTtlMinutes = configCacheTtlMinutes;
        this.cacheRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sequence-config-cache-refresh");
            t.setDaemon(true);
            return t;
        });
        // 启动定时任务：延迟 configCacheTtlMinutes 后首次执行，之后按固定间隔重复
        ConfigCacheRefresher refresher = new ConfigCacheRefresher(
                getCachedSequence(), sequenceRepository, configCache);
        cacheRefreshScheduler.scheduleAtFixedRate(
                refresher, configCacheTtlMinutes, configCacheTtlMinutes, TimeUnit.MINUTES);
        log.info("SequenceService initialized with configCacheTtlMinutes={}", configCacheTtlMinutes);
    }

    /**
     * 获取下一个序列值。
     * <p>
     * 根据序列配置的 {@code mode} 字段自动路由到对应的实现：
     * <ul>
     *   <li>{@code STRICT} → 从数据库 {@code SELECT ... FOR UPDATE} 分配，严格连续</li>
     *   <li>{@code CACHED} → 从内存双缓冲分配，高吞吐</li>
     * </ul>
     * 每次成功调用后，自动记录当前值到 {@link SessionHolder}，
     * 使得后续 {@link #currVal(String)} 可以获取到本会话的最新值。
     * </p>
     *
     * @param seqName 序列名称，不能为 {@code null} 或空字符串
     * @return 下一个序列值
     * @throws SequenceException 如果序列不存在、已耗尽或服务正在关闭
     */
    public long nextVal(String seqName) {
        Objects.requireNonNull(seqName, "seqName must not be null");

        SequenceConfig config = getConfig(seqName);
        SequenceStrategy strategy = strategyMap.get(config.getMode());
        if (strategy == null) {
            throw new SequenceException(SequenceErrorCode.INVALID_CONFIG,
                    "Unsupported mode: " + config.getMode());
        }

        log.debug("[SERVICE] Routing to {}: seqName={}, thread={}",
                config.getMode(), seqName, Thread.currentThread().getName());
        long value = strategy.nextVal(seqName, config);

        // 记录 currVal 会话语义（Oracle 兼容）
        SessionHolder.setCurrVal(seqName, value);

        log.debug("[SERVICE] nextVal({}) = {}, thread={}", seqName, value, Thread.currentThread().getName());
        return value;
    }

    /**
     * 获取当前会话中指定序列的最后一次 {@link #nextVal(String)} 返回值。
     * <p>
     * 对应 Oracle 序列的 {@code CURRVAL} 语义。
     * 必须在同一会话（线程）中先调用 {@link #nextVal(String)}，
     * 否则抛出 {@link SequenceException}。
     * </p>
     *
     * @param seqName 序列名称，不能为 {@code null}
     * @return 当前会话中最后一次 {@code nextVal} 的值
     * @throws SequenceException 如果当前会话中尚未调用 {@code nextVal}
     */
    public long currVal(String seqName) {
        return SessionHolder.getCurrVal(seqName);
    }

    /**
     * 获取序列配置（支持懒创建）。
     * <p>
     * 优先从本地缓存读取；缓存未命中时从数据库加载并写入缓存。
     * 若数据库中不存在对应行，自动按默认参数 INSERT 一条新记录（INSERT IGNORE 保证并发安全），
     * 然后重新查询并返回。使得调用方无需预创建即可首次 {@link #nextVal(String)}。
     * </p>
     *
     * @param seqName 序列名称，不能为 {@code null}
     * @return 序列配置
     */
    public SequenceConfig getConfig(String seqName) {
        return configCache.computeIfAbsent(seqName, name -> {
            log.debug("[SERVICE] Config cache miss for '{}', loading from DB, thread={}",
                    name, Thread.currentThread().getName());
            SequenceConfig config = sequenceRepository.selectConfig(name);
            if (config == null) {
                // Level 1 懒创建：自动 INSERT 默认配置行（并发安全）
                log.info("[SERVICE] Config not found for '{}', auto-creating with defaults", name);
                SequenceConfig defaultConfig = createDefaultConfig(name);
                sequenceRepository.insertConfigIgnore(defaultConfig);
                // 重新查询获取实际写入的行（INSERT IGNORE 可能已存在并发写入的行）
                config = sequenceRepository.selectConfig(name);
                if (config == null) {
                    // 理论上不应发生，保留防御逻辑
                    throw new SequenceException(SequenceErrorCode.SEQ_NOT_FOUND,
                            "seqName=" + name + " (auto-create failed)");
                }
                log.info("[SERVICE] Auto-created config for '{}': mode={}, startWith={}",
                        name, config.getMode(), config.getStartWith());
            } else {
                log.debug("[SERVICE] Config loaded from DB for '{}': mode={}, step={}, incrementBy={}",
                        name, config.getMode(), config.getStep(), config.getIncrementBy());
            }
            return config;
        });
    }

    /**
     * 创建默认序列配置。
     * <p>
     * 用于 {@link #getConfig(String)} 的懒创建场景。
     * 默认 mode=STRICT，支持时序流水号（如按日期重新生成）等动态序列场景。
     * </p>
     *
     * @param seqName 序列名称
     * @return 默认序列配置
     */
    private static SequenceConfig createDefaultConfig(String seqName) {
        return SequenceConfig.builder(seqName)
                .mode(Mode.STRICT)
                .build();
    }

    /**
     * 获取所有序列配置（全量查询）。
     * <p>
     * 直接从数据库读取，不经过缓存。适用于监控管理界面展示。
     * </p>
     *
     * @return 全部序列配置列表
     */
    public List<SequenceConfig> getAllConfigs() {
        return sequenceRepository.selectAllConfig();
    }

    /**
     * 从缓存中移除指定序列的配置。
     * <p>
     * 配置更新后调用此方法，下次 {@link #nextVal(String)} 调用将重新从数据库加载。
     * </p>
     *
     * @param seqName 序列名称，不能为 {@code null}
     */
    public void evictConfig(String seqName) {
        configCache.remove(seqName);
        log.debug("[SERVICE] Config cache evicted for '{}'", seqName);
    }

    /**
     * 清除全部配置缓存。
     * <p>
     * 批量配置更新后可调用此方法使整个缓存失效。
     * </p>
     */
    public void clearCache() {
        int size = configCache.size();
        configCache.clear();
        log.debug("[SERVICE] Config cache cleared, evicted {} entries", size);
    }

    /**
     * 停止序列服务，关闭配置缓存定时刷新调度器。
     * <p>
     * 由 Spring 容器在上下文关闭时调用（{@code destroyMethod="stop"}），
     * 优雅关闭 {@link #cacheRefreshScheduler}，等待正在执行的刷新任务完成。
     * </p>
     */
    public void stop() {
        cacheRefreshScheduler.shutdown();
        try {
            if (!cacheRefreshScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                cacheRefreshScheduler.shutdownNow();
                log.warn("Config cache refresh scheduler did not terminate in 3s, forced shutdown");
            }
        } catch (InterruptedException e) {
            cacheRefreshScheduler.shutdownNow();
            Thread.currentThread().interrupt();
            log.warn("Config cache refresh scheduler shutdown interrupted");
        }
        log.info("SequenceService stopped, config cache refresh scheduler shut down");
    }

    /**
     * 获取 {@link CachedSequence} 实例。
     * <p>
     * 用于优雅关闭场景，外部可通过此引用调用
     * {@link CachedSequence#transitionTo(ServiceState)} 和
     * {@link CachedSequence#persistUnusedSegments()}。
     * </p>
     *
     * @return CachedSequence 实例
     */
    public CachedSequence getCachedSequence() {
        SequenceStrategy strategy = strategyMap.get(Mode.CACHED);
        return strategy instanceof CachedSequence ? (CachedSequence) strategy : null;
    }

    /**
     * 获取指定序列的当前状态信息。
     * <p>
     * 返回包含配置信息、会话 currVal、双缓冲状态的综合快照。
     * 用于监控管理和故障排查。
     * </p>
     *
     * @param seqName 序列名称
     * @return 序列状态信息
     */
    public SequenceInfo getSequenceInfo(String seqName) {
        SequenceConfig config = getConfig(seqName);

        long currVal = 0;
        boolean hasCurrVal = false;
        try {
            currVal = currVal(seqName);
            hasCurrVal = true;
        } catch (SequenceException e) {
            // 当前会话尚未调用 nextVal，currVal 不可用
        }

        DoubleBuffer buffer = null;
        if (config.getMode() == Mode.CACHED) {
            CachedSequence cached = getCachedSequence();
            if (cached != null) {
                buffer = cached.getBuffer(seqName);
            }
        }

        return new SequenceInfo(config, currVal, hasCurrVal, buffer);
    }

    /**
     * 序列状态信息。
     * <p>
     * 封装序列的配置信息、当前会话的 currVal 值以及双缓冲状态，
     * 用于监控界面展示和运行时诊断。
     * </p>
     */
    public static class SequenceInfo {

        private final SequenceConfig config;
        private final long currVal;
        private final boolean hasCurrVal;
        private final DoubleBuffer buffer;

        /**
         * 构造序列状态信息。
         *
         * @param config     序列配置
         * @param currVal    当前会话 currVal（仅在 {@code hasCurrVal} 为 {@code true} 时有效）
         * @param hasCurrVal 当前会话是否已调用过 nextVal
         * @param buffer     双缓冲状态（CACHED 模式），可能为 {@code null}
         */
        public SequenceInfo(SequenceConfig config, long currVal,
                            boolean hasCurrVal, DoubleBuffer buffer) {
            this.config = config;
            this.currVal = currVal;
            this.hasCurrVal = hasCurrVal;
            this.buffer = buffer;
        }

        public SequenceConfig getConfig() {
            return config;
        }

        public long getCurrVal() {
            return currVal;
        }

        public boolean isHasCurrVal() {
            return hasCurrVal;
        }

        public DoubleBuffer getBuffer() {
            return buffer;
        }

        /**
         * 获取当前和备用 Segment 的总剩余数量。
         * <p>
         * 仅在 CACHED 模式下有效；STRICT 模式下返回 0。
         * </p>
         *
         * @return 剩余可用数量
         */
        public long getRemaining() {
            return buffer != null ? buffer.remaining() : 0;
        }
    }
}
