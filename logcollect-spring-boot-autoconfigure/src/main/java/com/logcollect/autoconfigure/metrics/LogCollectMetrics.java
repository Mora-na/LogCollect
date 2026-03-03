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

public class LogCollectMetrics implements com.logcollect.api.metrics.LogCollectMetrics {

    private final MeterRegistry registry;
    private final String prefix;
    private final DegradeFileManager degradeFileManager;

    private final ConcurrentHashMap<String, Counter> collectedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> discardedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> persistedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> persistFailedCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> flushCounters = new ConcurrentHashMap<String, Counter>();
    private final ConcurrentHashMap<String, Counter> overflowCounters = new ConcurrentHashMap<String, Counter>();
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

    @Override
    public void incrementCollected(String method, String level, String mode) {
        String methodTag = normalizeMethodKey(method);
        String levelTag = safeValue(level, "UNKNOWN");
        String modeTag = safeValue(mode, "UNKNOWN");
        counter(collectedCounters,
                methodTag + "_" + levelTag + "_" + modeTag,
                prefix + ".collected.total",
                "method", methodTag,
                "level", levelTag,
                "mode", modeTag).increment();
        totalCollected.incrementAndGet();
    }

    @Override
    public void incrementDiscarded(String method, String reason) {
        String methodTag = normalizeMethodKey(method);
        String reasonTag = normalizeReason(reason);
        counter(discardedCounters,
                methodTag + "_" + reasonTag,
                prefix + ".discarded.total",
                "method", methodTag,
                "reason", reasonTag).increment();
        totalDiscarded.incrementAndGet();
    }

    @Override
    public void incrementPersisted(String method, String mode) {
        String methodTag = normalizeMethodKey(method);
        String modeTag = safeValue(mode, "UNKNOWN");
        counter(persistedCounters,
                methodTag + "_" + modeTag,
                prefix + ".persisted.total",
                "method", methodTag,
                "mode", modeTag).increment();
        totalPersisted.incrementAndGet();
    }

    @Override
    public void incrementPersistFailed(String method) {
        String methodTag = normalizeMethodKey(method);
        counter(persistFailedCounters,
                methodTag,
                prefix + ".persist.failed.total",
                "method", methodTag).increment();
    }

    @Override
    public void incrementFlush(String method, String mode, String trigger) {
        String methodTag = normalizeMethodKey(method);
        String modeTag = safeValue(mode, "UNKNOWN");
        String triggerTag = safeValue(trigger, "unknown");
        counter(flushCounters,
                methodTag + "_" + modeTag + "_" + triggerTag,
                prefix + ".flush.total",
                "method", methodTag,
                "mode", modeTag,
                "trigger", triggerTag).increment();
        totalFlushes.incrementAndGet();
    }

    @Override
    public void incrementBufferOverflow(String method, String overflowPolicy) {
        String methodTag = normalizeMethodKey(method);
        String overflowTag = safeValue(overflowPolicy, "UNKNOWN");
        counter(overflowCounters,
                methodTag + "_" + overflowTag,
                prefix + ".buffer.overflow.total",
                "method", methodTag,
                "overflowPolicy", overflowTag).increment();
    }

    @Override
    public void incrementDegradeTriggered(String type, String method) {
        String typeTag = safeValue(type, "unknown");
        String methodTag = normalizeMethodKey(method);
        counter(degradeCounters,
                methodTag + "_" + typeTag,
                prefix + ".degrade.triggered.total",
                "type", typeTag,
                "method", methodTag).increment();
    }

    @Override
    public void incrementCircuitRecovered(String method) {
        String methodTag = normalizeMethodKey(method);
        counter(circuitRecoveredCounters,
                methodTag,
                prefix + ".circuit.recovered.total",
                "method", methodTag).increment();
    }

    @Override
    public void incrementSanitizeHits(String method) {
        String methodTag = normalizeMethodKey(method);
        counter(sanitizeCounters,
                methodTag,
                prefix + ".security.sanitize.hits.total",
                "method", methodTag).increment();
        totalSanitizeHits.incrementAndGet();
    }

    @Override
    public void incrementMaskHits(String method) {
        String methodTag = normalizeMethodKey(method);
        counter(maskCounters,
                methodTag,
                prefix + ".security.mask.hits.total",
                "method", methodTag).increment();
        totalMaskHits.incrementAndGet();
    }

    @Override
    public void incrementConfigRefresh(String source) {
        counter(configRefreshCounters,
                source,
                prefix + ".config.refresh.total",
                "source", source).increment();
    }

    @Override
    public void incrementHandlerTimeout(String method) {
        String methodTag = normalizeMethodKey(method);
        counter(handlerTimeoutCounters,
                methodTag,
                prefix + ".handler.timeout.total",
                "method", methodTag).increment();
    }

    @Override
    public void updateBufferUtilization(String method, double utilization) {
        String methodTag = normalizeMethodKey(method);
        AtomicReference<Double> gaugeValue = bufferUtilizations.computeIfAbsent(methodTag, key -> {
            AtomicReference<Double> value = new AtomicReference<Double>(0.0d);
            Gauge.builder(prefix + ".buffer.utilization", value, AtomicReference::get)
                    .tag("method", methodTag)
                    .register(registry);
            return value;
        });
        gaugeValue.set(utilization);
    }

    @Override
    public void updateGlobalBufferUtilization(double utilization) {
        globalBufferUtilization.set(utilization);
    }

    public void registerCircuitBreakerGauge(String method, LogCollectCircuitBreaker cb) {
        String methodTag = normalizeMethodKey(method);
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
                .tag("method", methodTag)
                .register(registry);
    }

    public void recordActiveCollectionStart() {
        activeCollections.incrementAndGet();
    }

    public void recordActiveCollectionEnd() {
        activeCollections.decrementAndGet();
    }

    @Override
    public Object startPersistTimer() {
        return Timer.start(registry);
    }

    @Override
    public void stopPersistTimer(Object sample, String method, String mode) {
        if (!(sample instanceof Timer.Sample)) {
            return;
        }
        String methodTag = normalizeMethodKey(method);
        String modeTag = safeValue(mode, "UNKNOWN");
        Timer.Sample timerSample = (Timer.Sample) sample;
        Timer timer = persistTimers.computeIfAbsent(methodTag + "_" + modeTag,
                key -> Timer.builder(prefix + ".persist.duration")
                        .tag("method", methodTag)
                        .tag("mode", modeTag)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        timerSample.stop(timer);
    }

    @Override
    public Object startSecurityTimer() {
        return Timer.start(registry);
    }

    @Override
    public void stopSecurityTimer(Object sample, String method) {
        if (!(sample instanceof Timer.Sample)) {
            return;
        }
        String methodTag = normalizeMethodKey(method);
        Timer.Sample timerSample = (Timer.Sample) sample;
        Timer timer = securityTimers.computeIfAbsent(methodTag,
                key -> Timer.builder(prefix + ".security.pipeline.duration")
                        .tag("method", methodTag)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        timerSample.stop(timer);
    }

    public void recordHandlerDuration(String method, String phase, long durationMs) {
        String methodTag = normalizeMethodKey(method);
        String phaseTag = safeValue(phase, "unknown");
        Timer timer = handlerTimers.computeIfAbsent(methodTag + "_" + phaseTag,
                key -> Timer.builder(prefix + ".handler.duration")
                        .tag("method", methodTag)
                        .tag("phase", phaseTag)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry));
        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    private String normalizeMethodKey(String methodSignature) {
        String source = safeValue(methodSignature, "unknown");
        int hashIndex = source.indexOf('#');
        if (hashIndex > 0) {
            int classStart = source.lastIndexOf('.', hashIndex);
            if (classStart >= 0 && classStart + 1 < source.length()) {
                return source.substring(classStart + 1);
            }
        }
        return source;
    }

    private String normalizeReason(String reason) {
        String value = safeValue(reason, "unknown");
        if ("level_filter".equals(value)
                || "logger_filter".equals(value)
                || "handler_filter".equals(value)
                || "logcollect_ignore".equals(value)) {
            return "filtered";
        }
        if ("backpressure_skip_low_level".equals(value) || "backpressure_pause".equals(value)) {
            return "backpressure";
        }
        return value;
    }

    private String safeValue(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
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
