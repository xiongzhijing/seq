
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 配置缓存定时刷新任务。
 * <p>
 * 由 {@link SequenceService} 的定时调度器周期性调用，执行三项维护操作：
 * <ol>
 *   <li>清理已从配置表中删除的序列的 DoubleBuffer</li>
 *   <li>清空配置缓存（下次访问时从数据库重新加载）</li>
 *   <li>删除 30 天前的旧号段分配记录</li>
 * </ol>
 * <p>
 * 实现 {@link Runnable}，可直接传给 {@link java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate}。
 *
 * @author XZJ
 */
public class ConfigCacheRefresher implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ConfigCacheRefresher.class);

    /** 号段缓存模式序列生成器，用于清理过期 DoubleBuffer */
    private final CachedSequence cachedSequence;

    /** 数据访问层，用于查询全量配置和删除旧记录 */
    private final SequenceRepository sequenceRepository;

    /** 配置缓存引用（与 SequenceService 共享同一实例） */
    private final ConcurrentMap<String, SequenceConfig> configCache;

    /** 连续失败计数器，3 次以上升级为 ERROR 日志 */
    private final AtomicInteger refreshFailCount = new AtomicInteger(0);

    /**
     * 构造配置缓存刷新任务。
     *
     * @param cachedSequence     号段缓存模式序列生成器
     * @param sequenceRepository 数据访问层
     * @param configCache        配置缓存（与 SequenceService 共享）
     */
    public ConfigCacheRefresher(CachedSequence cachedSequence,
                                SequenceRepository sequenceRepository,
                                ConcurrentMap<String, SequenceConfig> configCache) {
        this.cachedSequence = cachedSequence;
        this.sequenceRepository = sequenceRepository;
        this.configCache = configCache;
    }

    @Override
    public void run() {
        try {
            cleanupStaleBuffers();
            refreshConfigCache();
            cleanupOldRecords();
            refreshFailCount.set(0);
        } catch (Exception e) {
            int failures = refreshFailCount.incrementAndGet();
            if (failures >= 3) {
                log.error("[REFRESHER] Periodic refresh failed {} times consecutively: {}",
                        failures, e.getMessage(), e);
            } else {
                log.warn("[REFRESHER] Periodic refresh task failed ({}): {}", failures, e.getMessage());
            }
        }
    }

    /**
     * 清理已从配置表中删除的序列的 DoubleBuffer。
     * <p>
     * 查询数据库中所有活跃序列名称，移除内存中已不在配置表中的 DoubleBuffer，
     * 防止长期运行后 bufferMap 无限增长。
     * </p>
     */
    void cleanupStaleBuffers() {
        List<SequenceConfig> allConfigs = sequenceRepository.selectAllConfig();
        Set<String> activeNames = new HashSet<>();
        for (SequenceConfig c : allConfigs) {
            activeNames.add(c.getSeqName());
        }
        if (cachedSequence != null) {
            cachedSequence.evictStaleBuffers(activeNames);
        }
    }

    /**
     * 刷新配置缓存。
     * <p>
     * 粗粒度清空整个缓存，确保 DBA 在数据库中修改配置后能在 TTL 时间内生效。
     * 下次访问时将从数据库重新加载。
     * </p>
     */
    void refreshConfigCache() {
        configCache.clear();
    }

    /**
     * 清理 30 天前的旧号段分配记录。
     * <p>
     * 删除 {@code sequence_record} 表中 {@code alloc_time} 超过 30 天的记录，
     * 防止审计表无限增长。此操作失败不影响主流程。
     * </p>
     */
    void cleanupOldRecords() {
        try {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -30);
            Date beforeTime = cal.getTime();
            int deleted = sequenceRepository.deleteOldRecords(beforeTime);
            if (deleted > 0) {
                log.info("[REFRESHER] Cleaned up {} old sequence records (alloc_time before {})", deleted, beforeTime);
            } else {
                log.debug("[REFRESHER] No old sequence records to clean up (alloc_time before {})", beforeTime);
            }
        } catch (Exception e) {
            log.warn("[REFRESHER] Failed to clean up old records: {}", e.getMessage());
        }
    }

    /**
     * 获取连续失败次数（用于测试）。
     *
     * @return 连续失败次数
     */
    int getRefreshFailCount() {
        return refreshFailCount.get();
    }
}
