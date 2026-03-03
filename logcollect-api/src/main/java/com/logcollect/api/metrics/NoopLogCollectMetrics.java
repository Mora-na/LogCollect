package com.logcollect.api.metrics;

/**
 * 空实现：Metrics 禁用或未注入时使用。
 *
 * <p>所有方法为空操作，JIT 编译器可将虚方法调用内联为 no-op，
 * 在热路径上的实际开销趋近于零。
 */
public final class NoopLogCollectMetrics implements LogCollectMetrics {

    public static final NoopLogCollectMetrics INSTANCE = new NoopLogCollectMetrics();

    private NoopLogCollectMetrics() {
    }

    @Override
    public void incrementDiscarded(String methodKey, String reason) {
    }

    @Override
    public void incrementCollected(String methodKey, String level, String mode) {
    }

    @Override
    public void incrementPersisted(String methodKey, String mode) {
    }

    @Override
    public void incrementPersistFailed(String methodKey) {
    }

    @Override
    public void incrementFlush(String methodKey, String mode, String trigger) {
    }

    @Override
    public void incrementBufferOverflow(String methodKey, String overflowPolicy) {
    }

    @Override
    public void incrementDegradeTriggered(String type, String methodKey) {
    }

    @Override
    public void incrementCircuitRecovered(String methodKey) {
    }

    @Override
    public void incrementSanitizeHits(String methodKey) {
    }

    @Override
    public void incrementMaskHits(String methodKey) {
    }

    @Override
    public void incrementHandlerTimeout(String methodKey) {
    }

    @Override
    public void updateBufferUtilization(String methodKey, double utilization) {
    }

    @Override
    public void updateGlobalBufferUtilization(double utilization) {
    }

    @Override
    public Object startSecurityTimer() {
        return null;
    }

    @Override
    public void stopSecurityTimer(Object timerSample, String methodKey) {
    }

    @Override
    public Object startPersistTimer() {
        return null;
    }

    @Override
    public void stopPersistTimer(Object timerSample, String methodKey, String mode) {
    }
}
