
package com.ccb.jx.seq.session;

import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 会话级序列值持有者。
 * <p>
 * 使用 {@link ThreadLocal} 实现 Oracle CURRVAL 会话语义。
 * 在 Spring Boot 环境中，一次 HTTP 请求的生命周期视为一个会话。
 * </p>
 *
 * <p><b>设计说明：</b></p>
 * <ul>
 *   <li>{@code ThreadLocal} 确保每个线程（HTTP 请求）拥有独立的序列值快照，
 *       天然线程安全，无需同步开销</li>
 *   <li>{@link #setCurrVal(String, long)} 在每次 {@code nextVal} 调用时记录最新值</li>
 *   <li>{@link #getCurrVal(String)} 返回当前会话最后一次 {@code nextVal} 的值，
 *       若未调用过 {@code nextVal} 则抛出 {@link SequenceException}</li>
 *   <li>{@link #clear()} 必须在每个会话结束时调用（如 {@code HandlerInterceptor.afterCompletion}），
 *       否则可能导致内存泄漏（ThreadLocal 经典问题）</li>
 * </ul>
 *
 * @author XZJ
 */
public final class SessionHolder {

    /**
     * 存储当前会话（线程）中每个序列的最后一次 nextVal 值。
     * <p>
     * key = 序列名称（seqName），value = 最后一次 nextVal 返回值。
     * 使用 {@link ThreadLocal#withInitial(java.util.function.Supplier)} 懒初始化，
     * 避免未使用 SessionHolder 的线程创建不必要的 HashMap 实例。
     * </p>
     */
    private static final ThreadLocal<Map<String, Long>> CURRVAL_HOLDER =
            ThreadLocal.withInitial(HashMap::new);

    private SessionHolder() {
        // 工具类，私有构造器阻止实例化（More Effective Java 第 4 条）
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 记录当前会话中指定序列的 nextVal 值。
     * <p>
     * 由 {@code SequenceService.nextVal()} 在每次成功获取序列值后调用。
     * 如果同一会话中相同序列被多次调用，后一次的值会覆盖前一次。
     * </p>
     *
     * @param seqName 序列名称，不能为 {@code null}
     * @param value   本次 {@code nextVal} 返回的序列值
     * @throws NullPointerException 如果 {@code seqName} 为 {@code null}
     */
    public static void setCurrVal(String seqName, long value) {
        CURRVAL_HOLDER.get().put(seqName, value);
    }

    /**
     * 获取当前会话中指定序列的 currVal 值。
     * <p>
     * 返回当前会话（线程）中最近一次 {@link #setCurrVal(String, long)} 记录的序列值。
     * 调用此方法前必须在同一会话中先调用 {@code SequenceService.nextVal()}，
     * 否则抛出 {@link SequenceException}。
     * </p>
     *
     * @param seqName 序列名称，不能为 {@code null}
     * @return 当前会话中最后一次 {@code nextVal} 的值
     * @throws SequenceException 如果当前会话中尚未调用 {@code nextVal}
     * @throws NullPointerException 如果 {@code seqName} 为 {@code null}
     */
    public static long getCurrVal(String seqName) {
        Map<String, Long> map = CURRVAL_HOLDER.get();
        Long value = map.get(seqName);
        if (value == null) {
            throw new SequenceException(SequenceErrorCode.CURRVAL_NOT_INITIALIZED,
                    "seqName=" + seqName);
        }
        return value;
    }

    /**
     * 清理当前会话的所有序列值。
     * <p>
     * <b>必须</b>在每个会话结束时调用，以释放 {@link ThreadLocal} 关联的
     * {@link Map} 实例，防止内存泄漏。
     * </p>
     *
     * <p>推荐在以下点调用：</p>
     * <ul>
     *   <li>{@code HandlerInterceptor.afterCompletion()} — Spring MVC 拦截器</li>
     *   <li>{@code Filter.doFilter()} 的 {@code finally} 块 — Servlet Filter</li>
     *   <li>手动线程的 {@code finally} 块</li>
     * </ul>
     */
    public static void clear() {
        CURRVAL_HOLDER.remove();
    }

    /**
     * 包装 Runnable，执行后自动清理 ThreadLocal。<p>
     * 用于非 HTTP 线程场景（定时任务、消息消费者、异步任务等），
     * 确保线程复用时不会泄漏上一次会话的序列值。
     * </p>
     *
     * @param task 原始任务
     * @return 包装后的 Runnable，执行完毕后自动调用 {@link #clear()}
     */
    public static Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } finally {
                clear();
            }
        };
    }

    /**
     * 包装 Callable，执行后自动清理 ThreadLocal。<p>
     * 用于非 HTTP 线程场景（定时任务、消息消费者、异步任务等），
     * 确保线程复用时不会泄漏上一次会话的序列值。
     * </p>
     *
     * @param task 原始任务
     * @param <T>  返回值类型
     * @return 包装后的 Callable，执行完毕后自动调用 {@link #clear()}
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        return () -> {
            try {
                return task.call();
            } finally {
                clear();
            }
        };
    }
}
