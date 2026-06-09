
package com.ccb.jx.seq.controller;

import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 序列服务全局异常处理器。
 * <p>
 * 捕获 {@link SequenceException} 并根据错误码返回精确的 HTTP 状态码，
 * 使 API 消费者能够区分资源不存在、配置错误、服务器内部错误等不同场景。
 * </p>
 *
 * @author XZJ
 */
@RestControllerAdvice
public class SequenceExceptionHandler {

    /**
     * 处理序列业务异常，根据错误码返回精确 HTTP 状态码。
     * <p>
     * 映射规则：
     * <ul>
     *   <li>{@code SEQ_NOT_FOUND} → 404 Not Found</li>
     *   <li>{@code CURRVAL_NOT_INITIALIZED} → 400 Bad Request</li>
     *   <li>{@code SEQ_EXHAUSTED} → 410 Gone</li>
     *   <li>{@code SHUTTING_DOWN} → 503 Service Unavailable</li>
     *   <li>其他 → 500 Internal Server Error</li>
     * </ul>
     * </p>
     *
     * @param e 序列异常
     * @return 带状态码的异常消息响应
     */
    @ExceptionHandler(SequenceException.class)
    public ResponseEntity<Map<String, Object>> handleSequenceException(SequenceException e) {
        HttpStatus status = resolveHttpStatus(e.getErrorCode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getErrorCode().getCode());
        body.put("message", e.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 根据错误码解析 HTTP 状态码。
     *
     * @param errorCode 序列错误码
     * @return 对应的 HTTP 状态码
     */
    private HttpStatus resolveHttpStatus(SequenceErrorCode errorCode) {
        switch (errorCode) {
            case SEQ_NOT_FOUND:
                return HttpStatus.NOT_FOUND;
            case CURRVAL_NOT_INITIALIZED:
                return HttpStatus.BAD_REQUEST;
            case SEQ_EXHAUSTED:
                return HttpStatus.GONE;
            case SHUTTING_DOWN:
                return HttpStatus.SERVICE_UNAVAILABLE;
            default:
                return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }
}
