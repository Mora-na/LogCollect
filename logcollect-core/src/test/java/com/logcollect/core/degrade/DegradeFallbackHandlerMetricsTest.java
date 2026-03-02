package com.logcollect.core.degrade;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    @Test
    void privateWrap_coverPrimitiveBranches() throws Exception {
        Method wrap = DegradeFallbackHandler.class.getDeclaredMethod("wrap", Class.class);
        wrap.setAccessible(true);

        assertThat(wrap.invoke(null, int.class)).isEqualTo(Integer.class);
        assertThat(wrap.invoke(null, long.class)).isEqualTo(Long.class);
        assertThat(wrap.invoke(null, boolean.class)).isEqualTo(Boolean.class);
        assertThat(wrap.invoke(null, double.class)).isEqualTo(Double.class);
        assertThat(wrap.invoke(null, String.class)).isEqualTo(String.class);
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
    public static final class MetricsStub {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private volatile String lastReason;

        public void incrementDiscarded(String method, String reason) {
            callCount.incrementAndGet();
            lastReason = reason;
        }
    }
}
