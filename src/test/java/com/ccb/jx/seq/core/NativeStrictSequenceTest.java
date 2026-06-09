
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.dialect.NativeSequenceDialect;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link NativeStrictSequence} 的单元测试。
 * <p>
 * 验证：nextVal 正常流程、Level 2 懒创建自愈、best-effort max_id 更新。
 * </p>
 *
 * @author XZJ
 */
@DisplayName("NativeStrictSequence 原生 Sequence 测试")
@ExtendWith(MockitoExtension.class)
class NativeStrictSequenceTest {

    @Mock
    private NativeSequenceDialect dialect;

    @Mock
    private SequenceRepository repository;

    private NativeStrictSequence sequence;

    private SequenceConfig config;

    @BeforeEach
    void setUp() {
        sequence = new NativeStrictSequence(dialect, repository);
        config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMaxId(0L);
        config.setMaxValue(Long.MAX_VALUE);
        config.setCycle(0);
    }

    @Test
    @DisplayName("正常流程：应委托 dialect.nextVal 并返回")
    void shouldDelegateToDialect() {
        when(dialect.nextVal(config)).thenReturn(100L);
        when(repository.updateMaxId("test_seq", 100L, 0L)).thenReturn(1);

        long value = sequence.nextVal("test_seq", config);

        assertEquals(100L, value);
        verify(dialect).nextVal(config);
        verify(repository).updateMaxId("test_seq", 100L, 0L);
    }

    @Test
    @DisplayName("Level 2 懒创建：DB Sequence 不存在时自动创建并重试")
    void shouldLazyCreateWhenSequenceNotExists() {
        SequenceException notExistEx = new SequenceException(
                SequenceErrorCode.NATIVE_SEQ_ERROR, "tdsql_nextval: sequence not exists",
                new RuntimeException("Table 'test_seq' doesn't exist"));

        when(dialect.nextVal(config))
                .thenThrow(notExistEx)          // 第一次抛异常
                .thenReturn(200L);              // 重试成功
        when(dialect.isSequenceNotExist(notExistEx)).thenReturn(true);
        doNothing().when(dialect).createSequence(config);
        when(repository.updateMaxId("test_seq", 200L, 0L)).thenReturn(1);

        long value = sequence.nextVal("test_seq", config);

        assertEquals(200L, value);
        verify(dialect, times(2)).nextVal(config);   // 第一次抛异常+重试 = 2次
        verify(dialect).createSequence(config);        // 创建
        verify(repository).updateMaxId("test_seq", 200L, 0L);
    }

    @Test
    @DisplayName("Level 2：非 Sequence 不存在异常应直接抛出")
    void shouldThrowOnNonNotExistError() {
        SequenceException dbError = new SequenceException(
                SequenceErrorCode.DB_ERROR, "Connection timeout");

        when(dialect.nextVal(config)).thenThrow(dbError);

        assertThrows(SequenceException.class, () -> sequence.nextVal("test_seq", config));
        verify(dialect, never()).createSequence(any());
    }

    @Test
    @DisplayName("max_id 更新失败应打 WARN 日志，不影响返回值")
    void shouldNotThrowWhenUpdateMaxIdFails() {
        when(dialect.nextVal(config)).thenReturn(300L);
        when(repository.updateMaxId("test_seq", 300L, 0L))
                .thenThrow(new RuntimeException("DB error"));

        long value = sequence.nextVal("test_seq", config);

        assertEquals(300L, value);
    }

    @Test
    @DisplayName("序列耗尽时（max_id >= max_value 且 cycle=false）应抛出 SEQ_EXHAUSTED")
    void shouldThrowWhenExhausted() {
        config.setMaxId(Long.MAX_VALUE);
        config.setMaxValue(Long.MAX_VALUE);
        config.setCycle(0);

        SequenceException ex = assertThrows(SequenceException.class,
                () -> sequence.nextVal("test_seq", config));
        assertEquals(SequenceErrorCode.SEQ_EXHAUSTED, ex.getErrorCode());
        verify(dialect, never()).nextVal(any());
    }
}
