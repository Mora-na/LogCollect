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

    private final ConcurrentHashMap<String, MethodMeters> methodMeters = new ConcurrentHashMap<String, MethodMeters>();
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
    private final AtomicLong totalFastPathHits = new AtomicLong(0);

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

    /**
     * 预注册方法级热点计数器，避免首次日志请求落到热路径创建 Meter。
     */
    public void prepareMethodMeters(String method) {
        methodMeters(method);
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
        MethodMeters meters = methodMeters(method);
        String levelTag = safeValue(level, "UNKNOWN");
        String modeTag = safeValue(mode, "UNKNOWN");
        meters.collected(levelTag, modeTag).increment();
        totalCollected.incrementAndGet();
    }

    @Override
    public void incrementDiscarded(String method, String reason) {
        MethodMeters meters = methodMeters(method);
        String reasonTag = normalizeReason(reason);
        meters.discarded(reasonTag).increment();
        totalDiscarded.incrementAndGet();
    }

    @Override
    public void incrementPersisted(String method, String mode) {
        MethodMeters meters = methodMeters(method);
        String modeTag = safeValue(mode, "UNKNOWN");
        meters.persisted(modeTag).increment();
        totalPersisted.incrementAndGet();
    }

    @Override
    public void incrementPersistFailed(String method) {
        methodMeters(method).persistFailedCounter.increment();
    }

    @Override
    public void incrementFlush(String method, String mode, String trigger) {
        MethodMeters meters = methodMeters(method);
        String modeTag = safeValue(mode, "UNKNOWN");
        String triggerTag = safeValue(trigger, "unknown");
        meters.flush(modeTag, triggerTag).increment();
        totalFlushes.incrementAndGet();
    }

    @Override
    public void incrementBufferOverflow(String method, String overflowPolicy) {
        MethodMeters meters = methodMeters(method);
        String overflowTag = safeValue(overflowPolicy, "UNKNOWN");
        meters.overflow(overflowTag).increment();
    }

    @Override
    public void incrementDegradeTriggered(String type, String method) {
        String typeTag = safeValue(type, "unknown");
        methodMeters(method).degrade(typeTag).increment();
    }

    @Override
    public void incrementCircuitRecovered(String method) {
        methodMeters(method).circuitRecoveredCounter.increment();
    }

    @Override
    public void incrementSanitizeHits(String method) {
        methodMeters(method).sanitizeCounter.increment();
        totalSanitizeHits.incrementAndGet();
    }

    @Override
    public void incrementMaskHits(String method) {
        methodMeters(method).maskCounter.increment();
        totalMaskHits.incrementAndGet();
    }

    @Override
    public void incrementFastPathHits(String method) {
        methodMeters(method).fastPathCounter.increment();
        totalFastPathHits.incrementAndGet();
    }

    @Override
    public void incrementConfigRefresh(String source) {
        String sourceTag = safeValue(source, "unknown");
        configRefreshCounters.computeIfAbsent(sourceTag, key -> Counter.builder(prefix + ".config.refresh.total")
                .tag("source", sourceTag)
                .register(registry)).increment();
    }

    @Override
    public void incrementHandlerTimeout(String method) {
        methodMeters(method).handlerTimeoutCounter.increment();
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

    public long getTotalFastPathHits() {
        return totalFastPathHits.get();
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

    private MethodMeters methodMeters(String method) {
        String methodTag = normalizeMethodKey(method);
        return methodMeters.computeIfAbsent(methodTag, key -> new MethodMeters(key));
    }

    private final class MethodMeters {
        private final String methodTag;
        private final Counter sanitizeCounter;
        private final Counter maskCounter;
        private final Counter fastPathCounter;
        private final Counter persistFailedCounter;
        private final Counter handlerTimeoutCounter;
        private final Counter circuitRecoveredCounter;
        private final ConcurrentHashMap<String, Counter> collectedCounters =
                new ConcurrentHashMap<String, Counter>();
        private final ConcurrentHashMap<String, Counter> discardedCounters =
                new ConcurrentHashMap<String, Counter>();
        private final ConcurrentHashMap<String, Counter> persistedCounters =
                new ConcurrentHashMap<String, Counter>();
        private final ConcurrentHashMap<String, Counter> flushCounters =
                new ConcurrentHashMap<String, Counter>();
        private final ConcurrentHashMap<String, Counter> overflowCounters =
                new ConcurrentHashMap<String, Counter>();
        private final ConcurrentHashMap<String, Counter> degradeCounters =
                new ConcurrentHashMap<String, Counter>();

        private MethodMeters(String methodTag) {
            this.methodTag = methodTag;
            this.sanitizeCounter = Counter.builder(prefix + ".security.sanitize.hits.total")
                    .tag("method", methodTag)
                    .register(registry);
            this.maskCounter = Counter.builder(prefix + ".security.mask.hits.total")
                    .tag("method", methodTag)
                    .register(registry);
            this.fastPathCounter = Counter.builder(prefix + ".security.fastpath.hits.total")
                    .tag("method", methodTag)
                    .register(registry);
            this.persistFailedCounter = Counter.builder(prefix + ".persist.failed.total")
                    .tag("method", methodTag)
                    .register(registry);
            this.handlerTimeoutCounter = Counter.builder(prefix + ".handler.timeout.total")
                    .tag("method", methodTag)
                    .register(registry);
            this.circuitRecoveredCounter = Counter.builder(prefix + ".circuit.recovered.total")
                    .tag("method", methodTag)
                    .register(registry);
        }

        private Counter collected(String levelTag, String modeTag) {
            String key = levelTag + "_" + modeTag;
            return collectedCounters.computeIfAbsent(key, k -> Counter.builder(prefix + ".collected.total")
                    .tag("method", methodTag)
                    .tag("level", levelTag)
                    .tag("mode", modeTag)
                    .register(registry));
        }

        private Counter discarded(String reasonTag) {
            return discardedCounters.computeIfAbsent(reasonTag, k -> Counter.builder(prefix + ".discarded.total")
                    .tag("method", methodTag)
                    .tag("reason", reasonTag)
                    .register(registry));
        }

        private Counter persisted(String modeTag) {
            return persistedCounters.computeIfAbsent(modeTag, k -> Counter.builder(prefix + ".persisted.total")
                    .tag("method", methodTag)
                    .tag("mode", modeTag)
                    .register(registry));
        }

        private Counter flush(String modeTag, String triggerTag) {
            String key = modeTag + "_" + triggerTag;
            return flushCounters.computeIfAbsent(key, k -> Counter.builder(prefix + ".flush.total")
                    .tag("method", methodTag)
                    .tag("mode", modeTag)
                    .tag("trigger", triggerTag)
                    .register(registry));
        }

        private Counter overflow(String overflowTag) {
            return overflowCounters.computeIfAbsent(overflowTag, k -> Counter.builder(prefix + ".buffer.overflow.total")
                    .tag("method", methodTag)
                    .tag("overflowPolicy", overflowTag)
                    .register(registry));
        }

        private Counter degrade(String typeTag) {
            return degradeCounters.computeIfAbsent(typeTag, k -> Counter.builder(prefix + ".degrade.triggered.total")
                    .tag("type", typeTag)
                    .tag("method", methodTag)
                    .register(registry));
        }
    }
}
