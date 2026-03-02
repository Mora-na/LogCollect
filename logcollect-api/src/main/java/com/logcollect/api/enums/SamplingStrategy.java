package com.logcollect.api.enums;

/**
 * 日志采样策略。
 */
public enum SamplingStrategy {
    /**
     * 按固定比例随机采样。
     */
    RATE,
    /**
     * 按固定计数间隔采样。
     */
    COUNT,
    /**
     * 根据系统缓冲水位自适应采样。
     */
    ADAPTIVE
}
