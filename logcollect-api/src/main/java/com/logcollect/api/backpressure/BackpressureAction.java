package com.logcollect.api.backpressure;

/**
 * 背压动作枚举。
 *
 * <p>用于描述日志系统在高压状态下对当前日志事件的处理决策。</p>
 *
 * @since 1.0.0
 */
public enum BackpressureAction {
    /**
     * 继续收集当前日志。
     */
    CONTINUE,
    /**
     * 跳过低级别日志（低于 WARN）。
     */
    SKIP_DEBUG_INFO,
    /**
     * 暂停收集当前日志。
     */
    PAUSE
}
