
package com.ccb.jx.seq.session;

import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SessionHolder} 的单元测试。
 * <p>
 * 测试 ThreadLocal 会话语义的正确性，包括 set/get、clear 和异常路径。
 * </p>
 *
 * @author XZJ
 */
@DisplayName("SessionHolder 会话语义测试")
class SessionHolderTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // 每个测试前后清理会话状态，防止测试间相互干扰
        SessionHolder.clear();
    }

    @Test
    @DisplayName("setCurrVal 后 getCurrVal 应返回相同的值")
    void shouldStoreAndRetrieveCurrVal() {
        SessionHolder.setCurrVal("test_seq", 42L);

        assertEquals(42L, SessionHolder.getCurrVal("test_seq"));
    }

    @Test
    @DisplayName("同一序列多次 setCurrVal 应覆盖之前的值")
    void shouldOverwriteValueOnMultipleCalls() {
        SessionHolder.setCurrVal("test_seq", 100L);
        SessionHolder.setCurrVal("test_seq", 200L);

        assertEquals(200L, SessionHolder.getCurrVal("test_seq"));
    }

    @Test
    @DisplayName("未调用 setCurrVal 时 getCurrVal 应抛出 CURRVAL_NOT_INITIALIZED")
    void shouldThrowWhenNotInitialized() {
        SequenceException ex = assertThrows(SequenceException.class,
                () -> SessionHolder.getCurrVal("test_seq"));

        assertEquals(SequenceErrorCode.CURRVAL_NOT_INITIALIZED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("test_seq"));
    }

    @Test
    @DisplayName("clear() 后 getCurrVal 应抛出异常")
    void shouldClearSessionValues() {
        SessionHolder.setCurrVal("test_seq", 42L);
        SessionHolder.clear();

        assertThrows(SequenceException.class,
                () -> SessionHolder.getCurrVal("test_seq"));
    }

    @Test
    @DisplayName("应支持多个序列独立存储")
    void shouldHandleMultipleSequences() {
        SessionHolder.setCurrVal("seq1", 10L);
        SessionHolder.setCurrVal("seq2", 20L);

        assertEquals(10L, SessionHolder.getCurrVal("seq1"));
        assertEquals(20L, SessionHolder.getCurrVal("seq2"));
    }

    @Test
    @DisplayName("clear() 后所有序列值应全部清除")
    void clearShouldRemoveAllSequences() {
        SessionHolder.setCurrVal("seq_a", 1L);
        SessionHolder.setCurrVal("seq_b", 2L);
        SessionHolder.clear();

        assertThrows(SequenceException.class, () -> SessionHolder.getCurrVal("seq_a"));
        assertThrows(SequenceException.class, () -> SessionHolder.getCurrVal("seq_b"));
    }

    @Test
    @DisplayName("不同序列的 getCurrVal 互不影响")
    void differentSequencesShouldNotInterfere() {
        SessionHolder.setCurrVal("seq_only", 999L);

        // 查询未设置的序列应抛出异常
        assertThrows(SequenceException.class,
                () -> SessionHolder.getCurrVal("other_seq"));

        // 已设置的序列不受影响
        assertEquals(999L, SessionHolder.getCurrVal("seq_only"));
    }
}
