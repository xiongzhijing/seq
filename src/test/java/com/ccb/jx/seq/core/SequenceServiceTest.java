
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.buffer.Segment;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;
import com.ccb.jx.seq.session.SessionHolder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SequenceService} 的单元测试。
 * <p>
 * 验证模式路由（STRICT/CACHED）、会话语义、配置缓存、config 懒创建等核心功能。
 * </p>
 *
 * @author XZJ
 */
@DisplayName("SequenceService 统一入口测试")
@ExtendWith(MockitoExtension.class)
class SequenceServiceTest {

    @Mock
    private SequenceStrategy strictSequence;

    @Mock
    private CachedSequence cachedSequence;

    @Mock
    private SequenceRepository sequenceRepository;

    private SequenceService sequenceService;

    @BeforeEach
    void setUp() {
        // 清理 SessionHolder，防止测试间干扰
        SessionHolder.clear();
        Map<Mode, SequenceStrategy> strategyMap = new HashMap<>();
        strategyMap.put(Mode.STRICT, strictSequence);
        strategyMap.put(Mode.CACHED, cachedSequence);
        sequenceService = new SequenceService(strategyMap, sequenceRepository);
    }

    // ==================== 模式路由测试 ====================

    @Test
    @DisplayName("STRICT 模式应路由到 StrictSequence.nextVal")
    void shouldRouteToStrictMode() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.STRICT);
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);
        when(strictSequence.nextVal("test_seq", config)).thenReturn(100L);

        long value = sequenceService.nextVal("test_seq");

        assertEquals(100L, value);
        verify(strictSequence).nextVal("test_seq", config);
        verify(cachedSequence, never()).nextVal(anyString(), any());
    }

    @Test
    @DisplayName("CACHED 模式应路由到 CachedSequence.nextVal")
    void shouldRouteToCachedMode() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.CACHED);
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);
        when(cachedSequence.nextVal("test_seq", config)).thenReturn(200L);

        long value = sequenceService.nextVal("test_seq");

        assertEquals(200L, value);
        verify(cachedSequence).nextVal("test_seq", config);
        verify(strictSequence, never()).nextVal(anyString(), any());
    }

    @Test
    @DisplayName("nextVal 后应记录 currVal 到会话")
    void shouldRecordCurrValAfterNextVal() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.CACHED);
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);
        when(cachedSequence.nextVal("test_seq", config)).thenReturn(42L);

        sequenceService.nextVal("test_seq");

        // 验证 currVal 是否记录
        long currVal = sequenceService.currVal("test_seq");
        assertEquals(42L, currVal);
    }

    // ==================== currVal 测试 ====================

    @Test
    @DisplayName("未调用 nextVal 直接调用 currVal 应抛出 CURRVAL_NOT_INITIALIZED")
    void shouldThrowCurrValWithoutNextVal() {
        SequenceException ex = assertThrows(SequenceException.class,
                () -> sequenceService.currVal("test_seq"));
        assertEquals(SequenceErrorCode.CURRVAL_NOT_INITIALIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("currVal 应返回本会话最近一次 nextVal 的值")
    void currValShouldReturnLatestNextVal() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.CACHED);
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);
        when(cachedSequence.nextVal("test_seq", config)).thenReturn(10L, 20L, 30L);

        sequenceService.nextVal("test_seq");
        assertEquals(10L, sequenceService.currVal("test_seq"));

        sequenceService.nextVal("test_seq");
        assertEquals(20L, sequenceService.currVal("test_seq"));

        sequenceService.nextVal("test_seq");
        assertEquals(30L, sequenceService.currVal("test_seq"));
    }

    // ==================== 配置缓存测试 ====================

    @Test
    @DisplayName("nextVal 时序列不存在应自动创建默认配置")
    void shouldAutoCreateConfigWhenNotFound() {
        // selectConfig 返回 null 触发懒创建
        when(sequenceRepository.selectConfig("test_seq"))
                .thenReturn(null)          // 首次 null
                .thenReturn(createStrictConfig("test_seq"));  // 重新查询返回已创建的行

        SequenceConfig autoCreated = sequenceService.getConfig("test_seq");

        assertNotNull(autoCreated);
        assertEquals("test_seq", autoCreated.getSeqName());
        assertEquals(Mode.STRICT, autoCreated.getMode());

        // 验证 insertConfigIgnore 被调用
        ArgumentCaptor<SequenceConfig> captor = ArgumentCaptor.forClass(SequenceConfig.class);
        verify(sequenceRepository).insertConfigIgnore(captor.capture());
        SequenceConfig inserted = captor.getValue();
        assertEquals("test_seq", inserted.getSeqName());
        assertEquals(Mode.STRICT, inserted.getMode());
        assertEquals(1L, inserted.getStartWith().longValue());
        assertEquals(Long.MAX_VALUE, inserted.getMaxValue().longValue());
    }

    @Test
    @DisplayName("getConfig 应缓存结果，重复调用只查一次 DB")
    void shouldCacheConfig() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.STRICT);
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);
        when(strictSequence.nextVal(anyString(), any(SequenceConfig.class))).thenReturn(1L);

        // 第一次调用：加载配置到缓存
        sequenceService.nextVal("test_seq");
        // 第二次调用：应使用缓存
        sequenceService.nextVal("test_seq");

        // 验证 selectConfig 只被调用一次
        verify(sequenceRepository, times(1)).selectConfig("test_seq");
    }

    @Test
    @DisplayName("evictConfig 应清除指定序列的缓存")
    void shouldEvictConfigCache() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.STRICT);
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);
        when(strictSequence.nextVal(anyString(), any(SequenceConfig.class))).thenReturn(1L);

        sequenceService.nextVal("test_seq"); // 加载缓存
        sequenceService.evictConfig("test_seq");

        // 清除后再次调用应重新查询 DB
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);
        sequenceService.nextVal("test_seq");

        verify(sequenceRepository, times(2)).selectConfig("test_seq");
    }

    @Test
    @DisplayName("clearCache 应清除全部缓存")
    void shouldClearAllCache() {
        SequenceConfig configStrict = new SequenceConfig();
        configStrict.setSeqName("strict_seq");
        configStrict.setMode(Mode.STRICT);

        SequenceConfig configCached = new SequenceConfig();
        configCached.setSeqName("cached_seq");
        configCached.setMode(Mode.CACHED);

        when(sequenceRepository.selectConfig("strict_seq")).thenReturn(configStrict);
        when(sequenceRepository.selectConfig("cached_seq")).thenReturn(configCached);
        when(strictSequence.nextVal(anyString(), any(SequenceConfig.class))).thenReturn(1L);
        when(cachedSequence.nextVal(eq("cached_seq"), any(SequenceConfig.class))).thenReturn(2L);

        sequenceService.nextVal("strict_seq");
        sequenceService.nextVal("cached_seq");
        sequenceService.clearCache();

        // 清除后重新查询
        when(sequenceRepository.selectConfig("strict_seq")).thenReturn(configStrict);
        when(sequenceRepository.selectConfig("cached_seq")).thenReturn(configCached);
        sequenceService.nextVal("strict_seq");
        sequenceService.nextVal("cached_seq");

        // 清除后应重新加载：首次 2 次 + 清除后 2 次 = 4 次
        verify(sequenceRepository, times(2)).selectConfig("strict_seq");
        verify(sequenceRepository, times(2)).selectConfig("cached_seq");
    }

    // ==================== getSequenceInfo 测试 ====================

    @Test
    @DisplayName("getSequenceInfo 应返回序列状态信息")
    void shouldGetSequenceInfo() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.STRICT);
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);

        SequenceService.SequenceInfo info = sequenceService.getSequenceInfo("test_seq");

        assertNotNull(info);
        assertSame(config, info.getConfig());
        assertFalse(info.isHasCurrVal());
        assertEquals(0L, info.getCurrVal());
        assertNull(info.getBuffer());
        assertEquals(0L, info.getRemaining());
    }

    @Test
    @DisplayName("getCachedSequence 应返回 CachedSequence 实例")
    void shouldReturnCachedSequence() {
        assertSame(cachedSequence, sequenceService.getCachedSequence());
    }

    // ==================== 构造器参数校验 ====================

    @Test
    @DisplayName("构造器应拒绝 null 参数")
    void shouldRejectNullConstructorArgs() {
        Map<Mode, SequenceStrategy> validMap = new HashMap<>();
        validMap.put(Mode.STRICT, strictSequence);
        validMap.put(Mode.CACHED, cachedSequence);

        assertThrows(NullPointerException.class,
                () -> new SequenceService(null, sequenceRepository));
        assertThrows(NullPointerException.class,
                () -> new SequenceService(validMap, null));
    }

    // ==================== getAllConfigs 测试 ====================

    @Test
    @DisplayName("getAllConfigs 应委托给 repository")
    void getAllConfigsShouldDelegateToRepository() {
        sequenceService.getAllConfigs();
        verify(sequenceRepository).selectAllConfig();
    }

    // ==================== S5 配置缓存定时刷新测试 ====================

    @Test
    @DisplayName("S5: configCacheTtlMinutes 为非正数时应抛出 IllegalArgumentException")
    void shouldThrowWhenTtlIsNonPositive() {
        Map<Mode, SequenceStrategy> strategyMap = new HashMap<>();
        strategyMap.put(Mode.STRICT, strictSequence);
        strategyMap.put(Mode.CACHED, cachedSequence);

        assertThrows(IllegalArgumentException.class,
                () -> new SequenceService(strategyMap, sequenceRepository, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SequenceService(strategyMap, sequenceRepository, -1));
    }

    @Test
    @DisplayName("S5: 三参构造器应正确设置 configCacheTtlMinutes")
    void shouldSetConfigCacheTtlMinutes() {
        Map<Mode, SequenceStrategy> strategyMap = new HashMap<>();
        strategyMap.put(Mode.STRICT, strictSequence);
        strategyMap.put(Mode.CACHED, cachedSequence);

        SequenceService service = new SequenceService(strategyMap, sequenceRepository, 10L);
        // 验证服务正常启动且不抛异常
        assertNotNull(service);
        service.stop();
    }

    @Test
    @DisplayName("S5: clearCache 后再次访问应重新从 DB 加载配置")
    void shouldReloadConfigAfterClearCache() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.STRICT);
        when(sequenceRepository.selectConfig("test_seq")).thenReturn(config);
        when(strictSequence.nextVal(anyString(), any(SequenceConfig.class))).thenReturn(1L);

        // 第一次调用：加载配置到缓存
        sequenceService.nextVal("test_seq");
        verify(sequenceRepository, times(1)).selectConfig("test_seq");

        // 清空缓存
        sequenceService.clearCache();

        // 清空后再次调用应重新查询 DB
        sequenceService.nextVal("test_seq");
        verify(sequenceRepository, times(2)).selectConfig("test_seq");
    }

    @Test
    @DisplayName("S5: stop 应优雅关闭缓存刷新调度器")
    void shouldStopCacheRefreshScheduler() {
        Map<Mode, SequenceStrategy> strategyMap = new HashMap<>();
        strategyMap.put(Mode.STRICT, strictSequence);
        strategyMap.put(Mode.CACHED, cachedSequence);

        SequenceService service = new SequenceService(strategyMap, sequenceRepository, 5L);
        // stop 不应抛异常
        assertDoesNotThrow(service::stop);
    }

    // ==================== 辅助方法 ====================

    private SequenceConfig createStrictConfig(String seqName) {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName(seqName);
        config.setMode(Mode.STRICT);
        config.setStep(1000);
        config.setIncrementBy(1);
        config.setMinValue(1L);
        config.setMaxValue(Long.MAX_VALUE);
        config.setCycle(0);
        config.setStartWith(1L);
        return config;
    }
}
