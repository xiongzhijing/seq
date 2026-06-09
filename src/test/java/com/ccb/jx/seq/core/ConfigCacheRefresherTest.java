
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ConfigCacheRefresher} 的单元测试。
 *
 * @author XZJ
 */
@DisplayName("ConfigCacheRefresher 定时刷新任务测试")
@ExtendWith(MockitoExtension.class)
class ConfigCacheRefresherTest {

    @Mock
    private CachedSequence cachedSequence;

    @Mock
    private SequenceRepository sequenceRepository;

    private ConcurrentMap<String, SequenceConfig> configCache;

    private ConfigCacheRefresher refresher;

    @BeforeEach
    void setUp() {
        configCache = new ConcurrentHashMap<>();
        refresher = new ConfigCacheRefresher(cachedSequence, sequenceRepository, configCache);
    }

    // ==================== cleanupStaleBuffers 测试 ====================

    @Test
    @DisplayName("cleanupStaleBuffers 应查询全量配置并调用 evictStaleBuffers")
    void shouldCleanupStaleBuffers() {
        SequenceConfig config1 = new SequenceConfig();
        config1.setSeqName("seq_a");
        SequenceConfig config2 = new SequenceConfig();
        config2.setSeqName("seq_b");
        when(sequenceRepository.selectAllConfig()).thenReturn(Arrays.asList(config1, config2));

        refresher.cleanupStaleBuffers();

        Set<String> expectedNames = new HashSet<>(Arrays.asList("seq_a", "seq_b"));
        verify(cachedSequence).evictStaleBuffers(expectedNames);
    }

    @Test
    @DisplayName("cleanupStaleBuffers 在 cachedSequence 为 null 时不应抛异常")
    void shouldHandleNullCachedSequence() {
        ConfigCacheRefresher noCacheRefresher = new ConfigCacheRefresher(
                null, sequenceRepository, configCache);
        when(sequenceRepository.selectAllConfig()).thenReturn(Collections.emptyList());

        assertDoesNotThrow(noCacheRefresher::cleanupStaleBuffers);
    }

    // ==================== refreshConfigCache 测试 ====================

    @Test
    @DisplayName("refreshConfigCache 应清空配置缓存")
    void shouldRefreshConfigCache() {
        configCache.put("seq_a", new SequenceConfig());
        configCache.put("seq_b", new SequenceConfig());
        assertEquals(2, configCache.size());

        refresher.refreshConfigCache();

        assertTrue(configCache.isEmpty());
    }

    // ==================== cleanupOldRecords 测试 ====================

    @Test
    @DisplayName("cleanupOldRecords 应调用 deleteOldRecords")
    void shouldCleanupOldRecords() {
        when(sequenceRepository.deleteOldRecords(any(Date.class))).thenReturn(5);

        refresher.cleanupOldRecords();

        verify(sequenceRepository).deleteOldRecords(any(Date.class));
    }

    @Test
    @DisplayName("cleanupOldRecords 失败时不应抛异常")
    void shouldNotThrowOnCleanupOldRecordsFailure() {
        when(sequenceRepository.deleteOldRecords(any(Date.class)))
                .thenThrow(new RuntimeException("DB error"));

        assertDoesNotThrow(refresher::cleanupOldRecords);
    }

    // ==================== run() 编排测试 ====================

    @Test
    @DisplayName("run 应按顺序执行三个步骤并重置失败计数")
    void shouldRunAllStepsAndResetFailCount() {
        when(sequenceRepository.selectAllConfig()).thenReturn(Collections.emptyList());
        when(sequenceRepository.deleteOldRecords(any(Date.class))).thenReturn(0);
        configCache.put("seq_a", new SequenceConfig());

        refresher.run();

        verify(cachedSequence).evictStaleBuffers(anySet());
        assertTrue(configCache.isEmpty());
        verify(sequenceRepository).deleteOldRecords(any(Date.class));
        assertEquals(0, refresher.getRefreshFailCount());
    }

    @Test
    @DisplayName("run 失败时应递增失败计数")
    void shouldIncrementFailCountOnRunFailure() {
        when(sequenceRepository.selectAllConfig()).thenThrow(new RuntimeException("DB down"));

        refresher.run();
        assertEquals(1, refresher.getRefreshFailCount());

        refresher.run();
        assertEquals(2, refresher.getRefreshFailCount());
    }

    @Test
    @DisplayName("run 成功后应重置失败计数")
    void shouldResetFailCountOnSuccessfulRun() {
        // 第一次失败
        when(sequenceRepository.selectAllConfig()).thenThrow(new RuntimeException("DB down"));
        refresher.run();
        assertEquals(1, refresher.getRefreshFailCount());

        // 重置 mock 并重新 stub 为成功
        reset(sequenceRepository);
        when(sequenceRepository.selectAllConfig()).thenReturn(Collections.emptyList());
        when(sequenceRepository.deleteOldRecords(any(Date.class))).thenReturn(0);
        refresher.run();
        assertEquals(0, refresher.getRefreshFailCount());
    }
}
