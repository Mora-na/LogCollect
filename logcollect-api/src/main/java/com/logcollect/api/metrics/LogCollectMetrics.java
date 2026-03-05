package com.logcollect.api.metrics;

/**
 * 日志收集框架的 Metrics 抽象接口。
 *
 * <p>定义在 logcollect-api（零外部依赖）中，adapter 模块通过接口类型引用，
 * autoconfigure 模块提供基于 Micrometer 的实现，消除反射调用开销。
 *
 * <p>当 Metrics 被禁用或未注入时，使用 {@link NoopLogCollectMetrics} 空实现，
 * 所有方法调用为空操作，JIT 可内联消除。
 */
public interface LogCollectMetrics {

    void incrementDiscarded(String methodKey, String reason);

    void incrementCollected(String methodKey, String level, String mode);

    void incrementPersisted(String methodKey, String mode);

    void incrementPersistFailed(String methodKey);

    void incrementFlush(String methodKey, String mode, String trigger);

    void incrementBufferOverflow(String methodKey, String overflowPolicy);

    void incrementDegradeTriggered(String type, String methodKey);

    default void incrementDirectFlush(String methodKey, String outcome) {
    }

    default void incrementOverflowDegraded(String methodKey, String strategy) {
    }

    default void incrementForceAllocateRejected(String methodKey) {
    }

    void incrementCircuitRecovered(String methodKey);

    void incrementSanitizeHits(String methodKey);

    void incrementMaskHits(String methodKey);

    default void incrementFastPathHits(String methodKey) {
    }

    default void incrementPipelineBackpressure(String methodKey, String level) {
    }

    default void incrementPipelineTimeout(String methodKey, String step) {
    }

    default void updatePipelineQueueUtilization(String methodKey, double utilization) {
    }

    default void updatePipelineConsumerIdleRatio(String consumerName, double idleRatio) {
    }

    default Object startPipelineProcessTimer() {
        return null;
    }

    default void stopPipelineProcessTimer(Object timerSample, String methodKey) {
    }

    void incrementHandlerTimeout(String methodKey);

    default void incrementConfigRefresh(String source) {
    }

    void updateBufferUtilization(String methodKey, double utilization);

    void updateGlobalBufferUtilization(double utilization);

    default void updateGlobalHardCeilingUtilization(double utilization) {
    }

    Object startSecurityTimer();

    void stopSecurityTimer(Object timerSample, String methodKey);

    Object startPersistTimer();

    void stopPersistTimer(Object timerSample, String methodKey, String mode);
}
