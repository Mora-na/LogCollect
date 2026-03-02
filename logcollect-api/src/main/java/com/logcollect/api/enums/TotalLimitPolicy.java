package com.logcollect.api.enums;

/**
 * 单次方法调用总量上限触发后的处理策略。
 */
public enum TotalLimitPolicy {
    /**
     * 立即停止继续收集日志。
     */
    STOP_COLLECTING,
    /**
     * 降级为仅收集 WARN 及以上日志。
     */
    DOWNGRADE_LEVEL,
    /**
     * 进入采样模式继续收集。
     */
    SAMPLE
}
