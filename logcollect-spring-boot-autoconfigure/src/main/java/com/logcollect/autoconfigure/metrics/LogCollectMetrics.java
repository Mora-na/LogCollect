package com.logcollect.autoconfigure.metrics;

import com.logcollect.autoconfigure.LogCollectProperties;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class LogCollectMetrics {

    private final MeterRegistry registry;
    private final String prefix;

    private final ConcurrentHashMap<String, Counter> collectedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> discardedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> persistedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> persistFailedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> flushCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> degradeCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> handlerTimeoutCounters = new ConcurrentHashMap<String, Counter>();

    private final ConcurrentHashMap<String, Timer> persistTimers = new ConcurrentHashMap<String, Timer>();
    private final ConcurrentHashMap<String, Timer> securityTimers = new ConcurrentHashMap<String, Timer>();
    private final ConcurrentHashMap<String, Timer> handlerTimers = new ConcurrentHashMap<String, Timer>();

    private final AtomicInteger activeCollections = new AtomicInteger(0);
    private final AtomicReference<Double> globalBufferUtilization = new AtomicReference<Double>(0.0d);
    private final ConcurrentHashMap<String, AtomicReference<Double>> bufferUtilizations =
            new ConcurrentHashMap<String, AtomicReference<Double>>();

    private final AtomicLong totalCollected = new AtomicLong(0);
    private final AtomicLong totalDiscarded = new AtomicLong(0);
    private final AtomicLong totalPersisted = new AtomicLong(0);
    private final AtomicLong totalFlushes = new AtomicLong(0);
    private final AtomicLong totalSanitizeHits = new AtomicLong(0);
    private final AtomicLong totalMaskHits = new AtomicLong(0);

    public LogCollectMetrics(MeterRegistry registry, LogCollectProperties properties) {
        this.registry = registry;
        String configured = properties == null ? null : properties.getGlobal().getMetrics().getPrefix();
        this.prefix = configured == null || configured.trim().isEmpty() ? "logcollect" : configured.trim();
        registerGlobalGauges();
    }

    private void registerGlobalGauges() {
        Gauge.builder(prefix + ".active.collections", activeCollections, AtomicInteger::get)
                .description("Currently active @LogCollect collections")
                .register(registry);

        Gauge.builder(prefix + ".buffer.global.utilization", globalBufferUtilization, AtomicReference::get)
                .description("Global buffer utilization (0.0~1.0)")
                .register(registry);
    }

    public void incrementCollected(String method, String level, String mode) {
        counter(collectedCounters,
                method + "_" + level + "_" + mode,
                prefix + ".collected.total",
                "method", method,
                "level", level,
                "mode", mode).increment();
        totalCollected.incrementAndGet();
    }

    public void incrementDiscarded(String method, String reason) {
        counter(discardedCounters,
                method + "_" + reason,
                prefix + ".discarded.total",
                "method", method,
                "reason", reason).increment();
        totalDiscarded.incrementAndGet();
    }

    public void incrementPersisted(String method, String mode) {
        counter(persistedCounters,
                method + "_" + mode,
                prefix + ".persisted.total",
                "method", method,
                "mode", mode).increment();
        totalPersisted.incrementAndGet();
    }

    public void incrementPersistFailed(String method) {
        counter(persistFailedCounters,
                method,
                prefix + ".persist.failed.total",
                "method", method).increment();
    }

    public void incrementFlush(String method, String mode, String trigger) {
        counter(flushCounters,
                method + "_" + mode + "_" + trigger,
                prefix + ".flush.total",
                "method", method,
                "mode", mode,
                "trigger", trigger).increment();
        totalFlushes.incrementAndGet();
    }

    public void incrementDegradeTriggered(String type, String method) {
        counter(degradeCounters,
                method + "_" + type,
                prefix + ".degrade.triggered.total",
                "type", type,
                "method", method).increment();
    }

    public void incrementCircuitRecovered(String method) {
        counter(new ConcurrentHashMap<String, Counter>(),
                method,
                prefix + ".circuit.recovered.total",
                "method", method).increment();
    }

    public void incrementSanitizeHits(String method) {
        counter(new ConcurrentHashMap<String, Counter>(),
                method,
                prefix + ".security.sanitize.hits.total",
                "method", method).increment();
        totalSanitizeHits.incrementAndGet();
    }

    public void incrementMaskHits(String method) {
        counter(new ConcurrentHashMap<String, Counter>(),
                method,
                prefix + ".security.mask.hits.total",
                "method", method).increment();
        totalMaskHits.incrementAndGet();
    }

    public void incrementConfigRefresh(String source) {
        counter(new ConcurrentHashMap<String, Counter>(),
                source,
                prefix + ".config.refresh.total",
                "source", source).increment();
    }

    public void incrementHandlerTimeout(String method) {
        counter(handlerTimeoutCounters,
                method,
                prefix + ".handler.timeout.total",
                "method", method).increment();
    }

    public void updateBufferUtilization(String method, double utilization) {
        AtomicReference<Double> gaugeValue = bufferUtilizations.computeIfAbsent(method, key -> {
            AtomicReference<Double> value = new AtomicReference<Double>(0.0d);
            Gauge.builder(prefix + ".buffer.utilization", value, AtomicReference::get)
                    .tag("method", method)
                    .register(registry);
            return value;
        });
        gaugeValue.set(utilization);
    }

    public void updateGlobalBufferUtilization(double utilization) {
        globalBufferUtilization.set(utilization);
    }

    public void registerCircuitBreakerGauge(String method, LogCollectCircuitBreaker cb) {
        Gauge.builder(prefix + ".circuit.state", cb, breaker -> {
                    LogCollectCircuitBreaker.State state = breaker.getState();
                    if (state == LogCollectCircuitBreaker.State.CLOSED) {
                        return 0;
                    }
                    if (state == LogCollectCircuitBreaker.State.OPEN) {
                        return 1;
                    }
                    if (state == LogCollectCircuitBreaker.State.HALF_OPEN) {
                        return 2;
                    }
                    return -1;
                })
                .tag("method", method)
                .register(registry);
    }

    public void recordActiveCollectionStart() {
        activeCollections.incrementAndGet();
    }

    public void recordActiveCollectionEnd() {
        activeCollections.decrementAndGet();
    }

    public Timer.Sample startPersistTimer() {
        return Timer.start(registry);
    }

    public void stopPersistTimer(Timer.Sample sample, String method, String mode) {
        if (sample == null) {
            return;
        }
        Timer timer = persistTimers.computeIfAbsent(method + "_" + mode,
                key -> Timer.builder(prefix + ".persist.duration")
                        .tag("method", method)
                        .tag("mode", mode)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        sample.stop(timer);
    }

    public Timer.Sample startSecurityTimer() {
        return Timer.start(registry);
    }

    public void stopSecurityTimer(Timer.Sample sample, String method) {
        if (sample == null) {
            return;
        }
        Timer timer = securityTimers.computeIfAbsent(method,
                key -> Timer.builder(prefix + ".security.pipeline.duration")
                        .tag("method", method)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        sample.stop(timer);
    }

    public void recordHandlerDuration(String method, String phase, long durationMs) {
        Timer timer = handlerTimers.computeIfAbsent(method + "_" + phase,
                key -> Timer.builder(prefix + ".handler.duration")
                        .tag("method", method)
                        .tag("phase", phase)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public int getActiveCollections() {
        return activeCollections.get();
    }

    public double getGlobalBufferUtilization() {
        return globalBufferUtilization.get();
    }

    public long getTotalCollected() {
        return totalCollected.get();
    }

    public long getTotalDiscarded() {
        return totalDiscarded.get();
    }

    public long getTotalPersisted() {
        return totalPersisted.get();
    }

    public long getTotalFlushes() {
        return totalFlushes.get();
    }

    public long getTotalSanitizeHits() {
        return totalSanitizeHits.get();
    }

    public long getTotalMaskHits() {
        return totalMaskHits.get();
    }

    private Counter counter(ConcurrentHashMap<String, Counter> cache,
                            String key,
                            String metricName,
                            String... tags) {
        return cache.computeIfAbsent(key, k -> Counter.builder(metricName).tags(tags).register(registry));
    }
}
