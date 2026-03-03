package com.logcollect.core.degrade;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DegradeFallbackHandlerMetricsTest {

    @AfterEach
    void cleanupQueue() throws Exception {
        AtomicInteger size = memoryQueueSize();
        size.set(0);
        Field queueField = DegradeFallbackHandler.class.getDeclaredField("MEMORY_QUEUE");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<String> queue = (ConcurrentLinkedQueue<String>) queueField.get(null);
        queue.clear();
    }

    @Test
    void handleDegraded_allFallbackFail_nonBlocking_recordsMetric() throws Exception {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setEnableDegrade(true);
        config.setEnableMetrics(true);
        config.setBlockWhenDegradeFail(false);
        config.setDegradeStorage(DegradeStorage.FILE);

        LogCollectContext context = new LogCollectContext(
                "trace-metric",
                DegradeFallbackHandlerMetricsTest.class.getDeclaredMethod("marker"),
                new Object[0],
                config,
                new LogCollectHandler() {
                },
                null,
                null,
                CollectMode.SINGLE);

        context.setAttribute("__degradeFileManager", new DegradeFileManager(null, 1024, 1, null));
        MetricsStub metrics = new MetricsStub();
        context.setAttribute("__metrics", metrics);

        memoryQueueSize().set(1000);

        boolean result = DegradeFallbackHandler.handleDegraded(
                context, "persist_failed", Collections.singletonList("x"), "ERROR");
        assertThat(result).isFalse();
        assertThat(metrics.lastReason).isEqualTo("ultimate_discard");
        assertThat(metrics.callCount.get()).isEqualTo(1);
    }

    private AtomicInteger memoryQueueSize() throws Exception {
        Field sizeField = DegradeFallbackHandler.class.getDeclaredField("MEMORY_QUEUE_SIZE");
        sizeField.setAccessible(true);
        return (AtomicInteger) sizeField.get(null);
    }

    @SuppressWarnings("unused")
    private static void marker() {
    }

    @SuppressWarnings("unused")
    public static final class MetricsStub implements LogCollectMetrics {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private volatile String lastReason;

        @Override
        public void incrementDiscarded(String method, String reason) {
            callCount.incrementAndGet();
            lastReason = reason;
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
}
