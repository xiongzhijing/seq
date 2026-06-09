
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link StrictSequence} 的单元测试。
 * <p>
 * 验证严格连续模式的 nextVal 逻辑，包括正常分配、CYCLE 回绕（S3）、
 * FOR UPDATE 无重试（S4）、start_with 初始化（M2）等场景。
 * </p>
 *
 * @author XZJ
 */
@DisplayName("StrictSequence 严格连续模式测试")
@ExtendWith(MockitoExtension.class)
class StrictSequenceTest {

    @Mock
    private SequenceRepository sequenceRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private StrictSequence strictSequence;

    @BeforeEach
    void setUp() {
        strictSequence = new StrictSequence(sequenceRepository, transactionManager);
    }

    /**
     * 创建测试用的 SequenceConfig。
     */
    private SequenceConfig createConfig(long maxId, int incrementBy,
                                        long minValue, long maxValue, boolean cycle) {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMaxId(maxId);
        config.setIncrementBy(incrementBy);
        config.setMinValue(minValue);
        config.setMaxValue(maxValue);
        config.setCycle(cycle ? 1 : 0);
        config.setMode(Mode.STRICT);
        config.setStartWith(1L);
        return config;
    }

    @Test
    @DisplayName("正常分配：应返回 currentMaxId + incrementBy")
    void shouldReturnNextValSuccessfully() {
        SequenceConfig config = createConfig(1L, 1, 1L, 100L, false);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 2L, 1L)).thenReturn(1);

        long value = strictSequence.nextVal("test_seq", config);
        assertEquals(2L, value);

        verify(sequenceRepository).selectConfigForUpdate("test_seq");
        verify(sequenceRepository).updateMaxId("test_seq", 2L, 1L);
    }

    @Test
    @DisplayName("incrementBy > 1 时应按步幅递增")
    void shouldSupportIncrementBy() {
        SequenceConfig config = createConfig(10L, 5, 1L, 1000L, false);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 15L, 10L)).thenReturn(1);

        long value = strictSequence.nextVal("test_seq", config);
        assertEquals(15L, value);

        verify(sequenceRepository).updateMaxId("test_seq", 15L, 10L);
    }

    @Test
    @DisplayName("序列不存在时应抛出 SEQ_NOT_FOUND")
    void shouldThrowWhenSequenceNotFound() {
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(null);

        SequenceConfig config = createConfig(1L, 1, 1L, 100L, false);
        SequenceException ex = assertThrows(SequenceException.class,
                () -> strictSequence.nextVal("test_seq", config));
        assertEquals(SequenceErrorCode.SEQ_NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("test_seq"));
    }

    @Test
    @DisplayName("S4: FOR UPDATE 下 updateMaxId 失败应直接抛出 DB_ERROR，不重试")
    void shouldThrowDbErrorWithoutRetryWhenUpdateFails() {
        SequenceConfig config = createConfig(1L, 1, 1L, 100L, false);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 2L, 1L)).thenReturn(0);

        SequenceException ex = assertThrows(SequenceException.class,
                () -> strictSequence.nextVal("test_seq", config));
        assertEquals(SequenceErrorCode.DB_ERROR, ex.getErrorCode());

        // 验证无重试 — selectConfigForUpdate 和 updateMaxId 各只调用一次
        verify(sequenceRepository, times(1)).selectConfigForUpdate("test_seq");
        verify(sequenceRepository, times(1)).updateMaxId("test_seq", 2L, 1L);
    }

    @Test
    @DisplayName("非循环模式达到 maxValue 时应抛出 SEQ_EXHAUSTED")
    void shouldThrowWhenExhaustedWithoutCycle() {
        SequenceConfig config = createConfig(100L, 1, 1L, 100L, false);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);

        SequenceException ex = assertThrows(SequenceException.class,
                () -> strictSequence.nextVal("test_seq", config));
        assertEquals(SequenceErrorCode.SEQ_EXHAUSTED, ex.getErrorCode());
        verify(sequenceRepository, never()).updateMaxId(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("S3: 循环模式达到 maxValue 后应回绕到 minValue，max_id 设为 minValue")
    void shouldCycleWhenExhaustedWithCycle() {
        SequenceConfig config = createConfig(100L, 1, 1L, 100L, true);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        // S3 fix: max_id = minValue = 1，下次 nextVal = 1 + 1 = 2
        when(sequenceRepository.updateMaxId("test_seq", 1L, 100L)).thenReturn(1);

        long value = strictSequence.nextVal("test_seq", config);
        assertEquals(1L, value);
        verify(sequenceRepository).updateMaxId("test_seq", 1L, 100L);
    }

    @Test
    @DisplayName("S3: CYCLE 回绕时 incrementBy > 1 应正确设置 max_id")
    void shouldCycleWithIncrementByGreaterThanOne() {
        SequenceConfig config = createConfig(100L, 5, 10L, 100L, true);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        // candidate = 100 + 5 = 105 > 100, cycle: nextVal = 10, newMaxId = 10
        when(sequenceRepository.updateMaxId("test_seq", 10L, 100L)).thenReturn(1);

        long value = strictSequence.nextVal("test_seq", config);
        assertEquals(10L, value);
        verify(sequenceRepository).updateMaxId("test_seq", 10L, 100L);
    }

    @Test
    @DisplayName("较大的 incrementBy 应正确计算 nextVal")
    void shouldCalculateNextValWithLargeIncrement() {
        SequenceConfig config = createConfig(90L, 10, 1L, 200L, false);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 100L, 90L)).thenReturn(1);

        long value = strictSequence.nextVal("test_seq", config);
        assertEquals(100L, value);
    }

    @Test
    @DisplayName("M2: start_with 初始化 — 首次 nextVal 应返回 start_with 值")
    void shouldReturnStartWithValueOnFirstCall() {
        SequenceConfig config = createConfig(1L, 1, 1L, 10000L, false);
        config.setStartWith(100L);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        // currentMaxId=1, startWith=100, so currentMaxId = 100 - 1 = 99
        // nextVal = 99 + 1 = 100, newMaxId = 100
        // originalMaxId = 1, so updateMaxId(seqName, 100, 1)
        when(sequenceRepository.updateMaxId("test_seq", 100L, 1L)).thenReturn(1);

        long value = strictSequence.nextVal("test_seq", config);
        assertEquals(100L, value);
        verify(sequenceRepository).updateMaxId("test_seq", 100L, 1L);
    }

    @Test
    @DisplayName("M2: start_with 为默认值时不应修正 max_id")
    void shouldNotModifyMaxIdWhenStartWithIsDefault() {
        SequenceConfig config = createConfig(50L, 1, 1L, 10000L, false);
        config.setStartWith(1L);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 51L, 50L)).thenReturn(1);

        long value = strictSequence.nextVal("test_seq", config);
        assertEquals(51L, value);
        verify(sequenceRepository).updateMaxId("test_seq", 51L, 50L);
    }

    @Test
    @DisplayName("M2: start_with > 1 且 incrementBy > 1 时应正确初始化")
    void shouldInitializeWithStartWithAndIncrementBy() {
        SequenceConfig config = createConfig(1L, 10, 1L, 100000L, false);
        config.setStartWith(500L);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        // currentMaxId=1, startWith=500, so currentMaxId = 500 - 10 = 490
        // nextVal = 490 + 10 = 500, newMaxId = 500
        // originalMaxId = 1
        when(sequenceRepository.updateMaxId("test_seq", 500L, 1L)).thenReturn(1);

        long value = strictSequence.nextVal("test_seq", config);
        assertEquals(500L, value);
        verify(sequenceRepository).updateMaxId("test_seq", 500L, 1L);
    }
}
