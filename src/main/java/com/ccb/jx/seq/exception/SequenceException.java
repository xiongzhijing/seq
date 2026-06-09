
package com.ccb.jx.seq.exception;

/**
 * 序列服务运行时异常。
 * <p>
 * 所有序列相关的异常均由此类表示，通过 {@link SequenceErrorCode} 区分错误类型。
 * 继承 {@link RuntimeException}，不强制调用方捕获，符合 Spring 声明式事务
 * 的默认回滚行为（回滚 {@link RuntimeException}）。
 *
 * @author XZJ
 */
public class SequenceException extends RuntimeException {

    private final SequenceErrorCode errorCode;

    /**
     * 使用指定的错误码构造异常。
     *
     * @param errorCode 错误码，不能为 null
     */
    public SequenceException(SequenceErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 使用指定的错误码和详细描述构造异常。
     *
     * @param errorCode 错误码，不能为 null
     * @param detail    详细错误描述
     */
    public SequenceException(SequenceErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + ": " + detail);
        this.errorCode = errorCode;
    }

    /**
     * 使用指定的错误码、详细描述和原始异常构造异常。
     *
     * @param errorCode 错误码，不能为 null
     * @param detail    详细错误描述
     * @param cause     原始异常（根因）
     */
    public SequenceException(SequenceErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.getMessage() + ": " + detail, cause);
        this.errorCode = errorCode;
    }

    /**
     * 返回异常对应的错误码。
     *
     * @return 错误码
     */
    public SequenceErrorCode getErrorCode() {
        return errorCode;
    }
}
