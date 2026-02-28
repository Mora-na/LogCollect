package com.logcollect.autoconfigure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LogCollectMetrics {
    private final Counter sanitizeHits;
    private final Counter maskHits;
    private final AtomicInteger activeCollections = new AtomicInteger(0);

    public LogCollectMetrics(MeterRegistry registry) {
        this.sanitizeHits = Counter.builder("logcollect.security.sanitize.hits.total").register(registry);
        this.maskHits = Counter.builder("logcollect.security.mask.hits.total").register(registry);
        Gauge.builder("logcollect.active.collections", activeCollections, AtomicInteger::get).register(registry);
    }

    public void recordSanitizeHit() {
        sanitizeHits.increment();
    }

    public void recordMaskHit() {
        maskHits.increment();
    }

    public void incrementActive() {
        activeCollections.incrementAndGet();
    }

    public void decrementActive() {
        activeCollections.decrementAndGet();
    }
}
