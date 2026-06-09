
package com.ccb.jx.seq.core;

/**
 * 序列服务生命周期状态。
 * <p>
 * 显式化服务状态转换，替代分散的 boolean 标志。
 * 状态转换规则：{@code RUNNING → SHUTTING_DOWN → SHUTDOWN}，单向不可逆。
 * </p>
 *
 * @author XZJ
 */
public enum ServiceState {

    /** 正常运行中，接受请求 */
    RUNNING,

    /** 关闭中，拒绝新请求 */
    SHUTTING_DOWN,

    /** 已关闭 */
    SHUTDOWN;

    /**
     * 当前状态是否接受新请求。
     *
     * @return {@code true} 如果处于 RUNNING 状态
     */
    public boolean acceptRequests() {
        return this == RUNNING;
    }

    /**
     * 判断是否可以转换到目标状态。
     * <p>
     * 合法转换：RUNNING → SHUTTING_DOWN → SHUTDOWN
     * </p>
     *
     * @param target 目标状态
     * @return {@code true} 如果转换合法
     */
    public boolean canTransitionTo(ServiceState target) {
        switch (this) {
            case RUNNING:
                return target == SHUTTING_DOWN;
            case SHUTTING_DOWN:
                return target == SHUTDOWN;
            default:
                return false;
        }
    }
}
