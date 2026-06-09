
package com.ccb.jx.seq.session;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * SessionHolder 自动清理拦截器。
 * <p>
 * 在每个 HTTP 请求完成后自动清理 {@link SessionHolder} 中的 {@link ThreadLocal} 数据，
 * 防止在线程池复用场景下发生内存泄漏。
 * </p>
 *
 * <h3>问题背景</h3>
 * <p>
 * Web 容器（Tomcat 等）使用线程池处理请求，线程在请求结束后被复用而非销毁。
 * 如果 {@link ThreadLocal} 中的数据未清理，会导致：
 * <ul>
 *   <li>内存泄漏 — Map 实例随线程生命周期驻留，无法被 GC</li>
 *   <li>数据串扰 — 同一线程处理的下一个请求可能读取到上一个请求的 CURRVAL</li>
 * </ul>
 * </p>
 *
 * <h3>注册方式</h3>
 * <p>
 * 通过 {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer}
 * 注册，拦截所有路径（{@code /**}），在 {@code afterCompletion} 阶段执行清理。
 * </p>
 *
 * @author XZJ
 */
public class SessionHolderCleanupInterceptor implements HandlerInterceptor {

    /**
     * 请求处理完成后清理 SessionHolder。
     * <p>
     * 无论请求处理成功还是抛出异常，此方法都会被 Spring MVC 调用，
     * 确保 {@link ThreadLocal} 数据不会残留到下一个请求。
     * </p>
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  处理器
     * @param ex       处理过程中抛出的异常（可能为 {@code null}）
     */
    @Override
    public void afterCompletion(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler, Exception ex) {
        SessionHolder.clear();
    }
}
