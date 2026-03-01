package com.logcollect.autoconfigure.metrics;

import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.autoconfigure.LogCollectProperties;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFileManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class LogCollectMetrics {

    private final MeterRegistry registry;
    private final String prefix;
    private final DegradeFileManager degradeFileManager;

    private final ConcurrentHashMap<String, Counter> collectedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> discardedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> persistedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> persistFailedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> flushCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> degradeCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> handlerTimeoutCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> circuitRecoveredCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> sanitizeCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> maskCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> configRefreshCounters = new ConcurrentHashMap<String, Counter>();

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

    public LogCollectMetrics(MeterRegistry registry, LogCollectProperties properties, DegradeFileManager degradeFileManager) {
        this.registry = registry;
        String configured = properties == null ? null : properties.getGlobal().getMetrics().getPrefix();
        this.prefix = configured == null || configured.trim().isEmpty() ? "logcollect" : configured.trim();
        this.degradeFileManager = degradeFileManager;
        registerGlobalGauges();
        registerDegradeFileGauges();
    }

    public boolean isEnabled(LogCollectConfig config) {
        return config != null && config.isEnableMetrics();
    }

    private void registerGlobalGauges() {
        Gauge.builder(prefix + ".active.collections", activeCollections, AtomicInteger::get)
                .description("Currently active @LogCollect collections")
                .register(registry);

        Gauge.builder(prefix + ".buffer.global.utilization", globalBufferUtilization, AtomicReference::get)
                .description("Global buffer utilization (0.0~1.0)")
                .register(registry);
    }

    private void registerDegradeFileGauges() {
        if (degradeFileManager == null || !degradeFileManager.isInitialized()) {
            return;
        }
        Gauge.builder(prefix + ".degrade.file.total.bytes", degradeFileManager, DegradeFileManager::getTotalSizeBytes)
                .description("Total size of degrade files in bytes")
                .baseUnit("bytes")
                .register(registry);
        Gauge.builder(prefix + ".degrade.file.count", degradeFileManager, DegradeFileManager::getFileCount)
                .description("Number of degrade files")
                .register(registry);
        Gauge.builder(prefix + ".degrade.file.disk.free.bytes", degradeFileManager, DegradeFileManager::getDiskFreeSpace)
                .description("Free disk space for degrade files in bytes")
                .baseUnit("bytes")
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
        counter(circuitRecoveredCounters,
                method,
                prefix + ".circuit.recovered.total",
                "method", method).increment();
    }

    public void incrementSanitizeHits(String method) {
        counter(sanitizeCounters,
                method,
                prefix + ".security.sanitize.hits.total",
                "method", method).increment();
        totalSanitizeHits.incrementAndGet();
    }

    public void incrementMaskHits(String method) {
        counter(maskCounters,
                method,
                prefix + ".security.mask.hits.total",
                "method", method).increment();
        totalMaskHits.incrementAndGet();
    }

    public void incrementConfigRefresh(String source) {
        counter(configRefreshCounters,
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

    public Map<String, Double> getBufferUtilizations() {
        if (bufferUtilizations.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> result = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, AtomicReference<Double>> entry : bufferUtilizations.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
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

    public String getLastPersistDurationP99() {
        double max = -1.0d;
        for (Timer timer : persistTimers.values()) {
            if (timer == null) {
                continue;
            }
            double p99 = timer.percentile(0.99, TimeUnit.MILLISECONDS);
            if (!Double.isNaN(p99) && p99 > max) {
                max = p99;
            }
        }
        if (max < 0) {
            return "N/A";
        }
        return String.format("%.1fms", max);
    }

    private Counter counter(ConcurrentHashMap<String, Counter> cache,
                            String key,
                            String metricName,
                            String... tags) {
        return cache.computeIfAbsent(key, k -> Counter.builder(metricName).tags(tags).register(registry));
    }
}
