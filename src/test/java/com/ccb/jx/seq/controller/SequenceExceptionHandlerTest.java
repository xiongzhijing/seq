
package com.ccb.jx.seq.controller;

import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("M7: SequenceExceptionHandler 异常处理测试")
class SequenceExceptionHandlerTest {

    private final SequenceExceptionHandler handler = new SequenceExceptionHandler();

    @Test
    @DisplayName("应将 SequenceException 映射为异常消息文本")
    void shouldReturnExceptionMessage() {
        SequenceException ex = new SequenceException(
                SequenceErrorCode.SEQ_NOT_FOUND, "seqName=missing_seq");

        ResponseEntity<Map<String, Object>> result = handler.handleSequenceException(ex);
        assertTrue(result.getBody().get("message").toString().contains("missing_seq"));
    }

    @Test
    @DisplayName("应处理不同类型的 SequenceException")
    void shouldHandleDifferentExceptionTypes() {
        SequenceException exhausted = new SequenceException(
                SequenceErrorCode.SEQ_EXHAUSTED, "maxValue=100");
        ResponseEntity<Map<String, Object>> result = handler.handleSequenceException(exhausted);
        assertNotNull(result.getBody());
        assertTrue(result.getBody().get("message").toString().contains("100"));
    }

    @Test
    @DisplayName("无 detail 的 SequenceException 应返回错误码消息")
    void shouldHandleExceptionWithoutDetail() {
        SequenceException ex = new SequenceException(SequenceErrorCode.CURRVAL_NOT_INITIALIZED);
        ResponseEntity<Map<String, Object>> result = handler.handleSequenceException(ex);
        assertNotNull(result.getBody());
        assertTrue(result.getBody().get("message").toString()
                .contains(SequenceErrorCode.CURRVAL_NOT_INITIALIZED.getMessage()));
    }
}
