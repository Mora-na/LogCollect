package com.logcollect.api.backpressure;

/**
 * 缓冲区背压回调。
 *
 * <p>框架会在日志入队前根据当前缓冲压力调用该回调，
 * 由回调决定是否继续收集、跳过低级别日志或暂停收集。</p>
 *
 * @since 1.0.0
 */
public interface BackpressureCallback {

    /**
     * 缓冲区压力回调。
     *
     * @param utilization 当前缓冲区利用率（0.0 ~ 1.0）
     * @return 背压动作，返回 null 等同于 {@link BackpressureAction#CONTINUE}
     */
    default BackpressureAction onPressure(double utilization) {
        return BackpressureAction.CONTINUE;
    }
}
