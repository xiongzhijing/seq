
package com.ccb.jx.seq.config;

import com.ccb.jx.seq.core.CachedSequence;
import com.ccb.jx.seq.core.ServiceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优雅关闭钩子。
 * <p>
 * 监听 Spring 容器的 {@link ContextClosedEvent}，并声明 {@link PreDestroy} 方法，
 * 在应用关闭时执行安全的序列服务停机流程。通过 {@link AtomicBoolean} 确保关闭逻辑
 * 无论触发几次（两个生命周期回调都可能被调用），都只执行一次。
 * </p>
 *
 * <h3>关闭顺序</h3>
 * <ol>
 *   <li><b>设置关闭标志</b> — 调用 {@link CachedSequence#setShuttingDown(boolean)}，
 *       后续新请求立即抛出 {@code SequenceException(SHUTTING_DOWN)}</li>
 *   <li><b>等待异步任务完成</b> — 关闭异步预加载线程池，最多等待
 *       {@value #SHUTDOWN_TIMEOUT_SECONDS} 秒，超时则强制终止</li>
 *   <li><b>持久化未使用号段</b> — 记录当前所有未消耗的号段信息到日志，便于审计</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * Spring 在关闭阶段会按以下顺序调用：
 * <ul>
 *   <li>{@link ContextClosedEvent} 广播（同步，由事件多播器线程执行）</li>
 *   <li>{@link PreDestroy} 回调（由销毁线程执行）</li>
 * </ul>
 * 二者可能在不同线程中并发执行。使用 {@link AtomicBoolean#compareAndSet} 保证
 * 关闭逻辑至多执行一次，且对并发可见。
 *
 * @author XZJ
 */
public class SequenceShutdownHook implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(SequenceShutdownHook.class);

    /** 关闭流程总超时时间（秒） */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final CachedSequence cachedSequence;

    /** 保证关闭逻辑只执行一次 */
    private final AtomicBoolean shutdownCompleted = new AtomicBoolean(false);

    /**
     * 构造优雅关闭钩子。
     * <p>
     * 使用构造器注入，遵循项目规范（无 {@code @Autowired} 字段注入）。
     * </p>
     *
     * @param cachedSequence 号段缓存序列服务
     */
    public SequenceShutdownHook(CachedSequence cachedSequence) {
        this.cachedSequence = cachedSequence;
    }

    /**
     * 响应 Spring 上下文关闭事件。
     * <p>
     * 当 ApplicationContext 关闭时（例如调用
     * {@code ConfigurableApplicationContext#close()}），Spring 会发布
     * {@link ContextClosedEvent}。此方法作为事件监听器被调用。
     * </p>
     *
     * @param event 上下文关闭事件
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        doShutdown();
    }

    /**
     * Bean 销毁前回调（安全兜底）。
     * <p>
     * 在 Bean 容器销毁时自动调用，作为 {@link #onApplicationEvent(ContextClosedEvent)}
     * 的补充。两个入口共用同一个 {@link #doShutdown()} 方法，由
     * {@link #shutdownCompleted} 确保不会重复执行。
     * </p>
     */
    @PreDestroy
    public void destroy() {
        doShutdown();
    }

    /**
     * 执行优雅关闭流程。
     * <p>
     * 使用 {@link AtomicBoolean#compareAndSet} 确保仅执行一次，
     * 防止 {@link PreDestroy} 和 {@link ContextClosedEvent} 两个入口重复触发。
     * </p>
     *
     * <h3>关闭步骤</h3>
     * <ol>
     *   <li>设置 {@link CachedSequence} 关闭标志，新请求快速失败</li>
     *   <li>关闭异步预加载线程池，等待正在执行的加载任务完成</li>
     *   <li>持久化未使用号段信息到日志，便于审计和恢复</li>
     * </ol>
     */
    private void doShutdown() {
        if (!shutdownCompleted.compareAndSet(false, true)) {
            log.info("Shutdown already completed, skipping");
            return;
        }

        log.info("Starting graceful shutdown of sequence service");

        // Step 1: 状态转换到 SHUTTING_DOWN，新请求快速失败
        cachedSequence.transitionTo(ServiceState.SHUTTING_DOWN);
        log.info("State transitioned to SHUTTING_DOWN, new requests will be rejected");

        // Step 2: 关闭异步预加载线程池，等待正在执行的加载任务完成
        ExecutorService executor = cachedSequence.getAsyncExecutor();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("Async executor did not terminate in {}s, forced shutdown",
                        SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            log.warn("Graceful shutdown interrupted during executor termination", e);
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        // Step 3: 持久化未使用号段信息（日志记录，后续审计和恢复）
        // 无论 Step 2 是否超时或中断，都必须执行此步
        cachedSequence.persistUnusedSegments();

        // Step 4: 状态转换到 SHUTDOWN
        cachedSequence.transitionTo(ServiceState.SHUTDOWN);

        log.info("Graceful shutdown of sequence service completed");
    }
}
