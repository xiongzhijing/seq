
package com.ccb.jx.seq.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SequenceException} 和 {@link SequenceErrorCode} 的单元测试。
 *
 * @author XZJ
 */
@DisplayName("SequenceException 异常测试")
class SequenceExceptionTest {

    @Test
    @DisplayName("仅传错误码构造，消息应为错误码的默认消息")
    void shouldCreateWithErrorCodeOnly() {
        SequenceException ex = new SequenceException(SequenceErrorCode.SEQ_NOT_FOUND);

        assertEquals(SequenceErrorCode.SEQ_NOT_FOUND, ex.getErrorCode());
        assertEquals(SequenceErrorCode.SEQ_NOT_FOUND.getMessage(), ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("传错误码和详情构造，消息应包含详情")
    void shouldCreateWithErrorCodeAndDetail() {
        SequenceException ex = new SequenceException(
                SequenceErrorCode.SEQ_EXHAUSTED, "seq=test_seq");

        assertEquals(SequenceErrorCode.SEQ_EXHAUSTED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("seq=test_seq"));
        assertTrue(ex.getMessage().contains(SequenceErrorCode.SEQ_EXHAUSTED.getMessage()));
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("传错误码、详情和根因构造，cause 应正确传递")
    void shouldCreateWithErrorCodeDetailAndCause() {
        RuntimeException cause = new RuntimeException("DB connection timeout");
        SequenceException ex = new SequenceException(
                SequenceErrorCode.DB_ERROR, "Connection failed", cause);

        assertEquals(SequenceErrorCode.DB_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Connection failed"));
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("应继承 RuntimeException")
    void shouldBeRuntimeException() {
        SequenceException ex = new SequenceException(SequenceErrorCode.INVALID_CONFIG);

        assertTrue(ex instanceof RuntimeException);
    }

    @Test
    @DisplayName("所有错误码的 getCode 和 getMessage 应非空")
    void allErrorCodesShouldHaveCodeAndMessage() {
        for (SequenceErrorCode code : SequenceErrorCode.values()) {
            assertNotNull(code.getCode(), "ErrorCode " + code.name() + " code should not be null");
            assertNotNull(code.getMessage(), "ErrorCode " + code.name() + " message should not be null");
            assertFalse(code.getCode().isEmpty(), "ErrorCode " + code.name() + " code should not be empty");
            assertFalse(code.getMessage().isEmpty(), "ErrorCode " + code.name() + " message should not be empty");
        }
    }

    @Test
    @DisplayName("formatted() 应返回 [code] message 格式")
    void formattedShouldReturnBracketedFormat() {
        String formatted = SequenceErrorCode.SEQ_NOT_FOUND.formatted();
        assertEquals("[SEQ_001] 序列不存在", formatted);
    }

    @Test
    @DisplayName("所有 errorCode 枚举应存在")
    void shouldHaveAllErrorCodes() {
        assertEquals(8, SequenceErrorCode.values().length);
        assertNotNull(SequenceErrorCode.valueOf("SEQ_NOT_FOUND"));
        assertNotNull(SequenceErrorCode.valueOf("SEQ_EXHAUSTED"));
        assertNotNull(SequenceErrorCode.valueOf("CURRVAL_NOT_INITIALIZED"));
        assertNotNull(SequenceErrorCode.valueOf("SHUTTING_DOWN"));
        assertNotNull(SequenceErrorCode.valueOf("DB_ERROR"));
        assertNotNull(SequenceErrorCode.valueOf("INVALID_CONFIG"));
        assertNotNull(SequenceErrorCode.valueOf("SEGMENT_LOAD_FAILED"));
        assertNotNull(SequenceErrorCode.valueOf("NATIVE_SEQ_ERROR"));
    }
}
