package com.logcollect.autoconfigure.metrics;

import com.logcollect.autoconfigure.LogCollectProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectMetricsTest {

    @Test
    void prepareMethodMeters_preRegistersAndCountsHotPathMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LogCollectMetrics metrics = new LogCollectMetrics(registry, new LogCollectProperties(), null);

        metrics.prepareMethodMeters("com.example.OrderService#submit");
        metrics.incrementCollected("com.example.OrderService#submit", "INFO", "AGGREGATE");
        metrics.incrementSanitizeHits("com.example.OrderService#submit");
        metrics.incrementMaskHits("com.example.OrderService#submit");
        metrics.incrementFastPathHits("com.example.OrderService#submit");
        metrics.incrementHandlerTimeout("com.example.OrderService#submit");

        Counter collected = registry.find("logcollect.collected.total")
                .tag("method", "OrderService#submit")
                .tag("level", "INFO")
                .tag("mode", "AGGREGATE")
                .counter();
        Counter sanitize = registry.find("logcollect.security.sanitize.hits.total")
                .tag("method", "OrderService#submit")
                .counter();
        Counter mask = registry.find("logcollect.security.mask.hits.total")
                .tag("method", "OrderService#submit")
                .counter();
        Counter fastPath = registry.find("logcollect.security.fastpath.hits.total")
                .tag("method", "OrderService#submit")
                .counter();
        Counter timeout = registry.find("logcollect.handler.timeout.total")
                .tag("method", "OrderService#submit")
                .counter();

        assertThat(collected).isNotNull();
        assertThat(sanitize).isNotNull();
        assertThat(mask).isNotNull();
        assertThat(fastPath).isNotNull();
        assertThat(timeout).isNotNull();
        assertThat(collected.count()).isEqualTo(1.0d);
        assertThat(sanitize.count()).isEqualTo(1.0d);
        assertThat(mask.count()).isEqualTo(1.0d);
        assertThat(fastPath.count()).isEqualTo(1.0d);
        assertThat(timeout.count()).isEqualTo(1.0d);
        assertThat(metrics.getTotalCollected()).isEqualTo(1L);
        assertThat(metrics.getTotalSanitizeHits()).isEqualTo(1L);
        assertThat(metrics.getTotalMaskHits()).isEqualTo(1L);
        assertThat(metrics.getTotalFastPathHits()).isEqualTo(1L);
    }

    @Test
    void incrementConfigRefresh_usesUnknownWhenSourceBlank() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LogCollectMetrics metrics = new LogCollectMetrics(registry, new LogCollectProperties(), null);

        metrics.incrementConfigRefresh("  ");

        Counter refresh = registry.find("logcollect.config.refresh.total")
                .tag("source", "unknown")
                .counter();
        assertThat(refresh).isNotNull();
        assertThat(refresh.count()).isEqualTo(1.0d);
    }
}
