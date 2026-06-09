
package com.ccb.jx.seq.model;

/**
 * 序列模式枚举。
 * <p>
 * {@link #STRICT} 模式为严格连续模式，每次 nextVal 调用都使用 SELECT ... FOR UPDATE
 * 进行悲观锁分配，保证严格连续无跳号，但吞吐量受限于锁竞争（约 ~200 TPS）。
 * <p>
 * {@link #CACHED} 模式为号段缓存模式，预取号段到内存双缓冲中，实现高性能分配
 * （&ge; 5000 TPS），但实例崩溃时可能产生号段空洞。
 *
 * @author XZJ
 */
public enum Mode {

    /**
     * 严格连续模式。每次 nextVal 都从数据库分配，保证连续无空洞。
     */
    STRICT,

    /**
     * 号段缓存模式。预取号段到内存，支持高吞吐，崩溃时有空洞风险。
     */
    CACHED
}
