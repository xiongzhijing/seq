
package com.ccb.jx.seq.exception;

/**
 * 序列服务错误码枚举。
 * <p>
 * 每个错误码由唯一标识码（code）和可读消息（message）组成，
 * 用于 {@link SequenceException} 的标准化异常描述。
 *
 * @author XZJ
 */
public enum SequenceErrorCode {

    /**
     * 序列不存在。
     * 当请求的序列名称在 sequence_config 表中未配置时抛出。
     */
    SEQ_NOT_FOUND("SEQ_001", "序列不存在"),

    /**
     * 序列已耗尽。
     * 序列达到最大值且未配置 CYCLE 时抛出。
     */
    SEQ_EXHAUSTED("SEQ_002", "序列已耗尽"),

    /**
     * currVal 尚未初始化。
     * 在当前会话（线程）中未调用 nextVal 就直接调用 currVal 时抛出。
     */
    CURRVAL_NOT_INITIALIZED("SEQ_003", "currVal 尚未在当前会话中初始化，请先调用 nextVal"),

    /**
     * 序列服务正在关闭。
     * 在 Spring 上下文关闭过程中收到新的序列请求时抛出。
     */
    SHUTTING_DOWN("SEQ_004", "序列服务正在关闭，拒绝新请求"),

    /**
     * 数据库操作失败。
     * 执行序列相关的数据库操作（查询、更新、插入）时发生异常。
     */
    DB_ERROR("SEQ_005", "数据库操作失败"),

    /**
     * 序列配置无效。
     * 从数据库读取的序列配置参数校验不通过时抛出。
     */
    INVALID_CONFIG("SEQ_006", "序列配置无效"),

    /**
     * 号段加载失败。
     * 在 CACHED 模式下，预取新号段到内存时失败。
     */
    SEGMENT_LOAD_FAILED("SEQ_007", "号段加载失败"),

    /**
     * 原生序列操作失败。
     * 在使用数据库原生 Sequence 时，DDL 或 tdsql_nextval 调用失败。
     */
    NATIVE_SEQ_ERROR("SEQ_008", "原生序列操作失败");

    private final String code;
    private final String message;

    SequenceErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误码标识，如 {@code "SEQ_001"}。
     *
     * @return 错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 返回错误码的可读消息。
     *
     * @return 错误消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 返回格式化的错误描述：{@code [code] message}。
     *
     * @return 格式化错误描述
     */
    public String formatted() {
        return "[" + code + "] " + message;
    }
}
