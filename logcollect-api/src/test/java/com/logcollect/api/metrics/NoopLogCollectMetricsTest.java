package com.logcollect.api.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NoopLogCollectMetricsTest {

    @Test
    void singletonInstance_exists() {
        assertThat(NoopLogCollectMetrics.INSTANCE).isNotNull();
    }

    @Test
    void allMethods_areNoop() {
        LogCollectMetrics metrics = NoopLogCollectMetrics.INSTANCE;
        assertThatCode(() -> {
            metrics.incrementDiscarded("m", "r");
            metrics.incrementCollected("m", "INFO", "SINGLE");
            metrics.incrementPersisted("m", "SINGLE");
            metrics.incrementPersistFailed("m");
            metrics.incrementDegradeTriggered("persist_failed", "m");
            metrics.incrementSanitizeHits("m");
            metrics.incrementMaskHits("m");
            metrics.stopSecurityTimer(metrics.startSecurityTimer(), "m");
            metrics.stopPersistTimer(metrics.startPersistTimer(), "m", "SINGLE");
        }).doesNotThrowAnyException();
    }

}
