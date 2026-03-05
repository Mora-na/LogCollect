package com.logcollect.core.config;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.backpressure.BackpressureCallback;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.enums.*;
import com.logcollect.api.format.LogLineDefaults;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogEntry;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.format.PatternCleaner;
import com.logcollect.core.format.PatternValidator;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import com.logcollect.core.util.DataSizeParser;
import com.logcollect.core.util.MethodKeyResolver;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负责合并多来源配置并输出方法级 {@link LogCollectConfig} 的解析器。
 *
 * <p>解析结果会按方法缓存；当配置中心变更时可通过 {@link #onConfigChange()} 清空缓存并同步关键全局项。
 */
public class LogCollectConfigResolver {

    private static final String GLOBAL_PREFIX = "logcollect.global.";
    private static final String METHODS_PREFIX = "logcollect.methods.";
    private static final Set<String> GLOBAL_ONLY_PREFIXES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "degrade.file.",
                    "buffer.total-max-bytes",
                    "buffer.hard-ceiling-bytes",
                    "buffer.counter-mode",
                    "buffer.estimation-factor",
                    "pipeline.consumer-threads",
                    "metrics.prefix",
                    "guard.max-content-length",
                    "guard.max-throwable-length"
            )));
    private static final Set<String> STARTUP_ONLY_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Collections.singletonList("metrics.prefix")));

    private final List<LogCollectConfigSource> sources;
    private final LogCollectLocalConfigCache cache;
    private final LogCollectGlobalSwitch globalSwitch;
    private final LogCollectMetrics metrics;
    private final GlobalBufferMemoryManager globalBufferMemoryManager;

    private final ConcurrentHashMap<String, LogCollectConfig> resolvedCache =
            new ConcurrentHashMap<String, LogCollectConfig>();
    private volatile Instant lastRefreshTime;
    private volatile Map<String, String> latestGlobalProperties = Collections.emptyMap();
    private volatile Map<String, String> startupOnlySnapshot = Collections.emptyMap();

    /**
     * 构造解析器（不绑定全局开关与指标对象）。
     *
     * @param sources 配置源集合，可为 null
     * @param cache   本地缓存，可为 null
     */
    public LogCollectConfigResolver(List<LogCollectConfigSource> sources, LogCollectLocalConfigCache cache) {
        this(sources, cache, null, null, null);
    }

    /**
     * 构造解析器。
     *
     * @param sources      配置源集合，可为 null
     * @param cache        本地缓存，可为 null
     * @param globalSwitch 全局开关同步目标，可为 null
     * @param metrics      指标桥接对象，可为 null
     */
    public LogCollectConfigResolver(List<LogCollectConfigSource> sources,
                                    LogCollectLocalConfigCache cache,
                                    LogCollectGlobalSwitch globalSwitch,
                                    LogCollectMetrics metrics) {
        this(sources, cache, globalSwitch, metrics, null);
    }

    public LogCollectConfigResolver(List<LogCollectConfigSource> sources,
                                    LogCollectLocalConfigCache cache,
                                    LogCollectGlobalSwitch globalSwitch,
                                    LogCollectMetrics metrics,
                                    GlobalBufferMemoryManager globalBufferMemoryManager) {
        List<LogCollectConfigSource> sorted = new ArrayList<LogCollectConfigSource>();
        if (sources != null) {
            sorted.addAll(sources);
        }
        sorted.sort(Comparator.comparingInt(LogCollectConfigSource::getOrder));
        this.sources = Collections.unmodifiableList(sorted);
        this.cache = cache;
        this.globalSwitch = globalSwitch;
        this.metrics = metrics == null ? NoopLogCollectMetrics.INSTANCE : metrics;
        this.globalBufferMemoryManager = globalBufferMemoryManager;
        syncGlobalEnabledFromSources();
        syncGlobalLogLinePatternFromSources();
        refreshStartupOnlySnapshot();
        syncGlobalBufferRuntime(Collections.<String, String>emptyMap(), "startup");
    }

    /**
     * 四级合并：
     * ④ 框架默认 &lt;- ③ 注解显式 &lt;- ② 配置中心全局 &lt;- ① 配置中心方法级
     *
     * @param method     目标方法
     * @param annotation 方法上的 {@link LogCollect} 注解，可为 null
     * @return 解析后的最终配置
     */
    public LogCollectConfig resolve(Method method, LogCollect annotation) {
        String displayMethodKey = MethodKeyResolver.toDisplayKey(method);
        String configMethodKey = MethodKeyResolver.toConfigKey(method);
        return resolvedCache.computeIfAbsent(displayMethodKey,
                key -> resolveInternal(configMethodKey, annotation));
    }

    private LogCollectConfig resolveInternal(String configMethodKey, LogCollect annotation) {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();

        // 第③级：注解显式
        mergeFromAnnotation(config, annotation);

        // 第②级：全局
        Map<String, String> globalProperties = loadGlobalProperties();
        mergeFromProperties(config, globalProperties);

        // 第①级：方法级（最高优先）
        Map<String, String> methodProperties = filterMethodLevelProperties(
                loadMethodProperties(configMethodKey), configMethodKey);
        mergeFromProperties(config, methodProperties);

        persistResolvedProperties(configMethodKey, methodProperties, globalProperties);
        return config;
    }

    /**
     * 处理配置变更，默认来源标记为 {@code unknown}。
     */
    public void onConfigChange() {
        onConfigChange("unknown", Collections.<String, String>emptyMap());
    }

    /**
     * 处理配置变更：清理解析缓存并重新同步关键全局项。
     *
     * @param source 变更来源标识，可为 null
     */
    public void onConfigChange(String source) {
        onConfigChange(source, Collections.<String, String>emptyMap());
    }

    public void onConfigChange(String source, Map<String, String> changedProperties) {
        Map<String, String> before = startupOnlySnapshot;
        clearCache();
        syncGlobalEnabledFromSources();
        syncGlobalLogLinePatternFromSources();
        syncGlobalBufferRuntime(changedProperties, source);
        logStartupOnlyRuntimeChanges(before, source);
        metrics.incrementConfigRefresh(source == null ? "unknown" : source);
        LogCollectInternalLogger.info("Config cache cleared due to config change from: {}",
                source == null ? "unknown" : source);
    }

    /**
     * 清空解析缓存。
     *
     * @return 清理前缓存大小
     */
    public int clearCache() {
        int size = resolvedCache.size();
        resolvedCache.clear();
        lastRefreshTime = Instant.now();
        return size;
    }

    /**
     * 获取当前解析缓存条目数。
     *
     * @return 缓存大小
     */
    public int getCacheSize() {
        return resolvedCache.size();
    }

    /**
     * 获取最近一次缓存刷新时间的可读字符串。
     *
     * @return ISO-8601 时间字符串；若从未刷新返回 {@code never}
     */
    public String getLastRefreshTimeFormatted() {
        return lastRefreshTime != null ? lastRefreshTime.toString() : "never";
    }

    /**
     * 获取最近一次缓存刷新时间。
     *
     * @return 最近刷新时间；若从未刷新返回 null
     */
    public Instant getLastRefreshTime() {
        return lastRefreshTime;
    }

    /**
     * 按方法键读取已缓存的配置。
     *
     * @param methodKey 方法键（展示键或规范化键）
     * @return 命中的缓存配置；未命中返回 null
     */
    public LogCollectConfig getCachedConfig(String methodKey) {
        if (methodKey == null) {
            return null;
        }
        String normalized = MethodKeyResolver.normalize(methodKey);
        LogCollectConfig config = resolvedCache.get(normalized);
        if (config == null) {
            config = resolvedCache.get(methodKey);
        }
        return config;
    }

    /**
     * 返回当前缓存的只读视图。
     *
     * @return 所有已缓存配置
     */
    public Map<String, LogCollectConfig> getAllCachedConfigs() {
        return Collections.unmodifiableMap(resolvedCache);
    }

    /**
     * 返回最近一次全局配置加载结果的只读快照。
     *
     * @return 全局配置键值对
     */
    public Map<String, String> getLatestGlobalProperties() {
        return latestGlobalProperties;
    }

    /**
     * 将当前可获取到的配置源内容持久化到本地缓存。
     */
    public void saveToLocalCache() {
        if (cache == null) {
            return;
        }
        Map<String, String> merged = collectAllPropertiesFromSources();
        if (!merged.isEmpty()) {
            cache.save(merged);
        }
    }

    private Map<String, String> loadGlobalProperties() {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        List<LogCollectConfigSource> lowToHigh = sortLowToHighPrioritySources();
        for (LogCollectConfigSource source : lowToHigh) {
            try {
                if (!source.isAvailable()) {
                    continue;
                }
                Map<String, String> props = source.getGlobalProperties();
                if (props != null && !props.isEmpty()) {
                    merged.putAll(props);
                }
            } catch (Exception e) {
                LogCollectInternalLogger.warn("Load global properties failed from {}", source.getType(), e);
            } catch (Error e) {
                LogCollectInternalLogger.error("Load global properties failed with fatal error from {}", source.getType(), e);
                throw e;
            }
        }
        if (!merged.isEmpty()) {
            latestGlobalProperties = Collections.unmodifiableMap(new LinkedHashMap<String, String>(merged));
            return merged;
        }
        Map<String, String> fromCache = loadGlobalPropertiesFromLocalCache();
        latestGlobalProperties = Collections.unmodifiableMap(new LinkedHashMap<String, String>(fromCache));
        return fromCache;
    }

    private Map<String, String> loadMethodProperties(String configMethodKey) {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        List<LogCollectConfigSource> lowToHigh = sortLowToHighPrioritySources();
        for (LogCollectConfigSource source : lowToHigh) {
            try {
                if (!source.isAvailable()) {
                    continue;
                }
                Map<String, String> props = source.getMethodProperties(configMethodKey);
                if (props != null && !props.isEmpty()) {
                    merged.putAll(props);
                }
            } catch (Exception e) {
                LogCollectInternalLogger.warn("Load method properties failed from {}", source.getType(), e);
            } catch (Error e) {
                LogCollectInternalLogger.error("Load method properties failed with fatal error from {}", source.getType(), e);
                throw e;
            }
        }
        if (!merged.isEmpty()) {
            return merged;
        }
        return loadMethodPropertiesFromLocalCache(configMethodKey);
    }

    private Map<String, String> filterMethodLevelProperties(Map<String, String> methodProperties, String methodKey) {
        if (methodProperties == null || methodProperties.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> filtered = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : methodProperties.entrySet()) {
            String key = entry.getKey();
            if (isGlobalOnlyKey(key)) {
                LogCollectInternalLogger.warn(
                        "Ignored method-level config for global-only key: method={}, key={}",
                        methodKey, key);
                continue;
            }
            filtered.put(key, entry.getValue());
        }
        return filtered;
    }

    private boolean isGlobalOnlyKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (String prefix : GLOBAL_ONLY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<LogCollectConfigSource> sortLowToHighPrioritySources() {
        List<LogCollectConfigSource> ordered = new ArrayList<LogCollectConfigSource>(sources);
        // 值越小优先级越高；这里从低优先到高优先合并，后写覆盖前写。
        ordered.sort((a, b) -> Integer.compare(b.getOrder(), a.getOrder()));
        return ordered;
    }

    private Map<String, String> collectAllPropertiesFromSources() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (LogCollectConfigSource source : sources) {
            try {
                if (!source.isAvailable()) {
                    continue;
                }
                Map<String, String> all = source.getAllProperties();
                if (all != null && !all.isEmpty()) {
                    result.putAll(all);
                    continue;
                }
                Map<String, String> global = source.getGlobalProperties();
                if (global != null) {
                    for (Map.Entry<String, String> entry : global.entrySet()) {
                        result.put(GLOBAL_PREFIX + entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception e) {
                LogCollectInternalLogger.warn("Load config source failed: {}", source.getType(), e);
            } catch (Error e) {
                LogCollectInternalLogger.error("Load config source failed with fatal error: {}", source.getType(), e);
                throw e;
            }
        }
        return result;
    }

    private void persistResolvedProperties(String configMethodKey,
                                           Map<String, String> methodProperties,
                                           Map<String, String> globalProperties) {
        if (cache == null) {
            return;
        }
        try {
            Map<String, String> merged = new LinkedHashMap<String, String>(cache.load());
            if (globalProperties != null) {
                for (Map.Entry<String, String> entry : globalProperties.entrySet()) {
                    merged.put(GLOBAL_PREFIX + entry.getKey(), entry.getValue());
                }
            }
            if (methodProperties != null && configMethodKey != null && !configMethodKey.isEmpty()) {
                String methodPrefix = METHODS_PREFIX + configMethodKey + ".";
                for (Map.Entry<String, String> entry : methodProperties.entrySet()) {
                    merged.put(methodPrefix + entry.getKey(), entry.getValue());
                }
            }
            if (!merged.isEmpty()) {
                cache.save(merged);
            }
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Persist resolved properties to local cache failed", e);
        } catch (Error e) {
            throw e;
        }
    }

    private Map<String, String> loadGlobalPropertiesFromLocalCache() {
        if (cache == null) {
            return Collections.emptyMap();
        }
        Map<String, String> cached = cache.load();
        if (cached.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> extracted = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : cached.entrySet()) {
            if (entry.getKey().startsWith(GLOBAL_PREFIX)) {
                extracted.put(entry.getKey().substring(GLOBAL_PREFIX.length()), entry.getValue());
            }
        }
        return extracted;
    }

    private Map<String, String> loadMethodPropertiesFromLocalCache(String methodKey) {
        if (cache == null) {
            return Collections.emptyMap();
        }
        Map<String, String> cached = cache.load();
        if (cached.isEmpty()) {
            return Collections.emptyMap();
        }
        String prefix = METHODS_PREFIX + methodKey + ".";
        Map<String, String> extracted = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : cached.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                extracted.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return extracted;
    }

    private void mergeFromAnnotation(LogCollectConfig config, LogCollect annotation) {
        if (annotation == null) {
            return;
        }

        if (annotation.handler() != null
                && annotation.handler() != com.logcollect.api.handler.LogCollectHandler.class) {
            config.setHandlerClass(annotation.handler());
        }
        if (!annotation.async()) {
            config.setAsync(false);
        }
        if (annotation.minLevel() != null && !annotation.minLevel().trim().isEmpty()) {
            config.setLevel(annotation.minLevel().trim());
        }
        if (annotation.excludeLoggers() != null && annotation.excludeLoggers().length > 0) {
            config.setExcludeLoggerPrefixes(annotation.excludeLoggers());
        }
        if (annotation.collectMode() != CollectMode.AUTO) {
            config.setCollectMode(annotation.collectMode());
        }

        if (!annotation.useBuffer()) {
            config.setUseBuffer(false);
        }
        if (annotation.maxBufferSize() != 100) {
            config.setMaxBufferSize(annotation.maxBufferSize());
        }
        if (!"1MB".equalsIgnoreCase(annotation.maxBufferBytes())) {
            config.setMaxBufferBytes(DataSizeParser.parseToBytes(annotation.maxBufferBytes()));
        }

        if (!annotation.enableDegrade()) {
            config.setEnableDegrade(false);
        }
        if (annotation.degradeFailThreshold() != 5) {
            config.setDegradeFailThreshold(annotation.degradeFailThreshold());
        }
        if (annotation.degradeStorage() != DegradeStorage.FILE) {
            config.setDegradeStorage(annotation.degradeStorage());
        }
        if (annotation.recoverIntervalSeconds() != 30) {
            config.setRecoverIntervalSeconds(annotation.recoverIntervalSeconds());
        }
        if (annotation.recoverMaxIntervalSeconds() != 300) {
            config.setRecoverMaxIntervalSeconds(annotation.recoverMaxIntervalSeconds());
        }
        if (annotation.halfOpenPassCount() != 3) {
            config.setHalfOpenPassCount(annotation.halfOpenPassCount());
        }
        if (annotation.halfOpenSuccessThreshold() != 3) {
            config.setHalfOpenSuccessThreshold(annotation.halfOpenSuccessThreshold());
        }
        if (annotation.totalLimitPolicy() != TotalLimitPolicy.STOP_COLLECTING) {
            config.setTotalLimitPolicy(annotation.totalLimitPolicy());
        }
        if (annotation.maxTotalCollect() != 100000) {
            config.setMaxTotalCollect(annotation.maxTotalCollect());
        }
        if (!"50MB".equalsIgnoreCase(annotation.maxTotalCollectBytes())) {
            config.setMaxTotalCollectBytes(DataSizeParser.parseToBytes(annotation.maxTotalCollectBytes()));
        }
        if (annotation.samplingRate() != 1.0d) {
            config.setSamplingRate(annotation.samplingRate());
        }
        if (annotation.samplingStrategy() != SamplingStrategy.RATE) {
            config.setSamplingStrategy(annotation.samplingStrategy());
        }
        if (annotation.backpressure() != null
                && annotation.backpressure() != BackpressureCallback.class) {
            config.setBackpressureCallbackClass(annotation.backpressure());
        }
        if (annotation.blockWhenDegradeFail()) {
            config.setBlockWhenDegradeFail(true);
        }

        if (!annotation.enableSanitize()) {
            config.setEnableSanitize(false);
        }
        if (annotation.sanitizer() != com.logcollect.api.sanitizer.LogSanitizer.class) {
            config.setSanitizerClass(annotation.sanitizer());
        }
        if (!annotation.enableMask()) {
            config.setEnableMask(false);
        }
        if (annotation.masker() != com.logcollect.api.masker.LogMasker.class) {
            config.setMaskerClass(annotation.masker());
        }
        if (annotation.pipelineTimeoutMs() != 50) {
            config.setSecurityPipelineTimeoutMs(annotation.pipelineTimeoutMs());
        }
        if (annotation.pipelineRingBufferCapacity() > 0) {
            config.setPipelineRingBufferCapacity(annotation.pipelineRingBufferCapacity());
        }
        if (annotation.pipelineQueueCapacity() != 8192) {
            config.setPipelineRingBufferCapacity(annotation.pipelineQueueCapacity());
        }

        if (annotation.handlerTimeoutMs() != 5000) {
            config.setHandlerTimeoutMs(annotation.handlerTimeoutMs());
        }
        if (annotation.transactionIsolation()) {
            config.setTransactionIsolation(true);
        }
        if (annotation.maxNestingDepth() != 10) {
            config.setMaxNestingDepth(annotation.maxNestingDepth());
        }

        if (!annotation.enableMetrics()) {
            config.setEnableMetrics(false);
        }
    }

    private void mergeFromProperties(LogCollectConfig config, Map<String, String> props) {
        if (props == null || props.isEmpty()) {
            return;
        }

        applyBoolean(props, "enabled", config::setEnabled);

        applyBoolean(props, "async", config::setAsync);
        applyString(props, "level", config::setLevel);
        applyCsv(props, "exclude-loggers", values -> config.setExcludeLoggerPrefixes(values.toArray(new String[0])));
        applyEnum(props, "log-framework", LogFramework.class, config::setLogFramework);
        applyEnum(props, "collect-mode", CollectMode.class, config::setCollectMode);

        applyBoolean(props, "buffer.enabled", config::setUseBuffer);
        applyInt(props, "buffer.max-size", config::setMaxBufferSize);
        applyDataSize(props, "buffer.max-bytes", config::setMaxBufferBytes);
        applyString(props, "buffer.overflow-strategy", config::setBufferOverflowStrategy);
        applyDataSize(props, "buffer.total-max-bytes", config::setGlobalBufferTotalMaxBytes);
        applyDataSize(props, "buffer.hard-ceiling-bytes", config::setGlobalBufferHardCeilingBytes);
        applyDouble(props, "buffer.estimation-factor", config::setGlobalBufferEstimationFactor);
        applyDataSize(props, "buffer.memory-sync-threshold-bytes", config::setBufferMemorySyncThresholdBytes);
        applyBoolean(props, "pipeline.enabled", config::setPipelineEnabled);
        applyInt(props, "pipeline.ring-buffer-capacity", config::setPipelineRingBufferCapacity);
        if (props.containsKey("pipeline.queue-capacity") && !props.containsKey("pipeline.ring-buffer-capacity")) {
            applyInt(props, "pipeline.queue-capacity", config::setPipelineRingBufferCapacity);
            LogCollectInternalLogger.warn(
                    "Detected deprecated config key 'pipeline.queue-capacity'; mapped to 'pipeline.ring-buffer-capacity'.");
        }
        applyInt(props, "pipeline.consumer-threads", config::setPipelineConsumerThreads);
        applyInt(props, "pipeline.overflow-queue-capacity", config::setPipelineOverflowQueueCapacity);
        applyInt(props, "pipeline.unpublished-slot-timeout-ms", config::setPipelineUnpublishedSlotTimeoutMs);
        applyString(props, "pipeline.consumer-idle-strategy", config::setPipelineConsumerIdleStrategy);
        applyDouble(props, "pipeline.backpressure-warning", config::setPipelineBackpressureWarning);
        applyDouble(props, "pipeline.backpressure-critical", config::setPipelineBackpressureCritical);
        applyInt(props, "pipeline.handoff-timeout-ms", config::setPipelineHandoffTimeoutMs);

        applyBoolean(props, "degrade.enabled", config::setEnableDegrade);
        applyInt(props, "degrade.fail-threshold", config::setDegradeFailThreshold);
        applyEnum(props, "degrade.storage", DegradeStorage.class, config::setDegradeStorage);
        applyInt(props, "degrade.recover-interval-seconds", config::setRecoverIntervalSeconds);
        applyInt(props, "degrade.recover-max-interval-seconds", config::setRecoverMaxIntervalSeconds);
        applyInt(props, "degrade.half-open-pass-count", config::setHalfOpenPassCount);
        applyInt(props, "degrade.half-open-success-threshold", config::setHalfOpenSuccessThreshold);
        applyInt(props, "degrade.window-size", config::setDegradeWindowSize);
        applyDouble(props, "degrade.failure-rate-threshold", config::setDegradeFailureRateThreshold);
        applyBoolean(props, "degrade.block-when-degrade-fail", config::setBlockWhenDegradeFail);

        applyString(props, "degrade.file.max-total-size", config::setDegradeFileMaxTotalSize);
        applyInt(props, "degrade.file.ttl-days", config::setDegradeFileTTLDays);
        applyBoolean(props, "degrade.file.encrypt-enabled", config::setEnableDegradeFileEncrypt);

        applyBoolean(props, "security.sanitize.enabled", config::setEnableSanitize);
        applyBoolean(props, "security.mask.enabled", config::setEnableMask);
        applyInt(props, "security.pipeline-timeout-ms", config::setSecurityPipelineTimeoutMs);
        applyInt(props, "guard.max-content-length", config::setGuardMaxContentLength);
        applyInt(props, "guard.max-throwable-length", config::setGuardMaxThrowableLength);

        applyInt(props, "handler-timeout-ms", config::setHandlerTimeoutMs);
        applyBoolean(props, "transaction-isolation", config::setTransactionIsolation);
        applyInt(props, "max-nesting-depth", config::setMaxNestingDepth);
        applyInt(props, "max-total-collect", config::setMaxTotalCollect);
        applyDataSize(props, "max-total-collect-bytes", config::setMaxTotalCollectBytes);
        applyEnum(props, "total-limit-policy", TotalLimitPolicy.class, config::setTotalLimitPolicy);
        applyDouble(props, "sampling-rate", config::setSamplingRate);
        applyEnum(props, "sampling-strategy", SamplingStrategy.class, config::setSamplingStrategy);

        applyBoolean(props, "metrics.enabled", config::setEnableMetrics);
    }

    private void syncGlobalEnabledFromSources() {
        if (globalSwitch == null) {
            return;
        }
        for (LogCollectConfigSource source : sources) {
            try {
                if (!source.isAvailable()) {
                    continue;
                }
                Map<String, String> globalProps = source.getGlobalProperties();
                if (globalProps != null && globalProps.containsKey("enabled")) {
                    globalSwitch.onConfigChange(Boolean.parseBoolean(globalProps.get("enabled")));
                    return;
                }
            } catch (Exception e) {
                LogCollectInternalLogger.warn("Sync global enabled from {} failed", source.getType(), e);
            } catch (Error e) {
                LogCollectInternalLogger.error("Sync global enabled from {} failed with fatal error", source.getType(), e);
                throw e;
            }
        }
    }

    private void syncGlobalLogLinePatternFromSources() {
        try {
            Map<String, String> globals = loadGlobalProperties();
            if (globals == null || globals.isEmpty()) {
                return;
            }
            String rawPattern = globals.get("format.log-line-pattern");
            if (rawPattern == null || rawPattern.trim().isEmpty()) {
                return;
            }
            String cleaned = PatternCleaner.clean(rawPattern);
            String validated = PatternValidator.validateAndClean(cleaned);
            LogLineDefaults.setDetectedPattern(validated);
        } catch (Exception e) {
            LogCollectInternalLogger.debug("Sync global log-line-pattern failed: {}", e.getMessage());
        } catch (Error e) {
            throw e;
        }
    }

    private void refreshStartupOnlySnapshot() {
        startupOnlySnapshot = snapshotStartupOnlyValues(loadGlobalProperties());
    }

    private void logStartupOnlyRuntimeChanges(Map<String, String> before, String source) {
        Map<String, String> after = snapshotStartupOnlyValues(loadGlobalProperties());
        for (String key : STARTUP_ONLY_KEYS) {
            String oldValue = before == null ? null : before.get(key);
            String newValue = after.get(key);
            if (!Objects.equals(oldValue, newValue)) {
                LogCollectInternalLogger.info(
                        "Config key '{}' changed from '{}' to '{}' (source={}) but is startup-only and ignored at runtime",
                        key, oldValue, newValue, source == null ? "unknown" : source);
            }
        }
        startupOnlySnapshot = after;
    }

    private Map<String, String> snapshotStartupOnlyValues(Map<String, String> globals) {
        if (globals == null || globals.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> snapshot = new LinkedHashMap<String, String>();
        for (String key : STARTUP_ONLY_KEYS) {
            if (globals.containsKey(key)) {
                snapshot.put(key, globals.get(key));
            }
        }
        return snapshot;
    }

    private void syncGlobalBufferRuntime(Map<String, String> changedProperties, String source) {
        Map<String, String> globals = loadGlobalProperties();
        syncDeprecatedGuardNotice(globals, changedProperties, source);
        syncEstimationFactor(globals, changedProperties, source);
        syncGlobalBufferLimits(globals, changedProperties, source);
        syncCounterModeWarning(globals, changedProperties, source);
        syncPipelineRuntimeWarnings(globals, changedProperties, source);
    }

    private void syncDeprecatedGuardNotice(Map<String, String> globals,
                                           Map<String, String> changedProperties,
                                           String source) {
        boolean contentConfigured = hasChangedOrConfigured(globals, changedProperties, "guard.max-content-length");
        boolean throwableConfigured = hasChangedOrConfigured(globals, changedProperties, "guard.max-throwable-length");
        if (!contentConfigured && !throwableConfigured) {
            return;
        }
        LogCollectInternalLogger.info(
                "Detected deprecated guard config (source={}): max-content-length/max-throwable-length are ignored since v1.2.0.",
                source == null ? "unknown" : source);
    }

    private void syncEstimationFactor(Map<String, String> globals,
                                      Map<String, String> changedProperties,
                                      String source) {
        if (!hasChangedOrConfigured(globals, changedProperties, "buffer.estimation-factor")) {
            return;
        }
        String value = globals.get("buffer.estimation-factor");
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        try {
            double factor = Double.parseDouble(value.trim());
            double old = LogEntry.getEstimationFactor();
            if (Double.compare(old, factor) == 0) {
                return;
            }
            LogEntry.setEstimationFactor(factor);
            LogCollectInternalLogger.info("Updated buffer estimation-factor: {} -> {} (source={})",
                    old, factor, source == null ? "unknown" : source);
        } catch (Exception ignored) {
            LogCollectInternalLogger.warn("Ignored invalid estimation-factor value: {}", value);
        }
    }

    private void syncGlobalBufferLimits(Map<String, String> globals,
                                        Map<String, String> changedProperties,
                                        String source) {
        if (globalBufferMemoryManager == null) {
            return;
        }

        boolean softInterested = hasChangedOrConfigured(globals, changedProperties, "buffer.total-max-bytes");
        boolean hardInterested = hasChangedOrConfigured(globals, changedProperties, "buffer.hard-ceiling-bytes");
        if (!softInterested && !hardInterested && changedProperties != null && !changedProperties.isEmpty()) {
            return;
        }

        long currentSoft = globalBufferMemoryManager.getMaxTotalBytes();
        long currentHard = globalBufferMemoryManager.getHardCeilingBytes();
        long newSoft = currentSoft;
        long newHard = currentHard;
        if (globals.containsKey("buffer.total-max-bytes")) {
            try {
                newSoft = DataSizeParser.parseToBytes(globals.get("buffer.total-max-bytes"));
            } catch (Exception ignored) {
                LogCollectInternalLogger.warn("Ignored invalid buffer.total-max-bytes: {}",
                        globals.get("buffer.total-max-bytes"));
            }
        }
        if (globals.containsKey("buffer.hard-ceiling-bytes")) {
            try {
                newHard = DataSizeParser.parseToBytes(globals.get("buffer.hard-ceiling-bytes"));
            } catch (Exception ignored) {
                LogCollectInternalLogger.warn("Ignored invalid buffer.hard-ceiling-bytes: {}",
                        globals.get("buffer.hard-ceiling-bytes"));
            }
        }

        if (newHard > 0 && newSoft > 0 && newHard < newSoft) {
            LogCollectInternalLogger.warn(
                    "Config change rejected (source={}): hard-ceiling-bytes({}) < total-max-bytes({}).",
                    source == null ? "unknown" : source,
                    newHard, newSoft);
            return;
        }
        if (newSoft == currentSoft && newHard == currentHard) {
            return;
        }
        globalBufferMemoryManager.updateLimits(newSoft, newHard);
        metrics.incrementConfigRefresh("buffer_limits");
    }

    private void syncCounterModeWarning(Map<String, String> globals,
                                        Map<String, String> changedProperties,
                                        String source) {
        if (!hasChangedOrConfigured(globals, changedProperties, "buffer.counter-mode")) {
            return;
        }
        String requested = globals.get("buffer.counter-mode");
        if (requested == null || requested.trim().isEmpty()) {
            return;
        }
        String current = globalBufferMemoryManager == null
                ? "UNKNOWN"
                : globalBufferMemoryManager.getCounterMode().name();
        if (!requested.trim().equalsIgnoreCase(current)) {
            LogCollectInternalLogger.warn(
                    "counter-mode change detected (source={}): current={}, requested={}. Requires restart, ignored at runtime.",
                    source == null ? "unknown" : source, current, requested);
        }
    }

    private void syncPipelineRuntimeWarnings(Map<String, String> globals,
                                             Map<String, String> changedProperties,
                                             String source) {
        if (hasChangedOrConfigured(globals, changedProperties, "pipeline.ring-buffer-capacity")
                || hasChangedOrConfigured(globals, changedProperties, "pipeline.queue-capacity")) {
            LogCollectInternalLogger.warn(
                    "pipeline.ring-buffer-capacity changed (source={}) but requires restart to take effect.",
                    source == null ? "unknown" : source);
        }
        if (hasChangedOrConfigured(globals, changedProperties, "pipeline.consumer-threads")) {
            LogCollectInternalLogger.warn(
                    "pipeline.consumer-threads changed (source={}) but requires restart to take effect.",
                    source == null ? "unknown" : source);
        }
    }

    private boolean hasChangedOrConfigured(Map<String, String> globals,
                                           Map<String, String> changedProperties,
                                           String relativeKey) {
        if (globals != null && globals.containsKey(relativeKey)) {
            return true;
        }
        if (changedProperties == null || changedProperties.isEmpty()) {
            return false;
        }
        return changedProperties.containsKey(relativeKey)
                || changedProperties.containsKey("logcollect.global." + relativeKey);
    }

    private interface BooleanConsumer {
        void accept(boolean value);
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private interface LongConsumer {
        void accept(long value);
    }

    private interface DoubleConsumer {
        void accept(double value);
    }

    private interface StringConsumer {
        void accept(String value);
    }

    private interface EnumConsumer<E> {
        void accept(E value);
    }

    private void applyBoolean(Map<String, String> props, String key, BooleanConsumer consumer) {
        String value = props.get(key);
        if (value != null) {
            consumer.accept(Boolean.parseBoolean(value));
        }
    }

    private void applyInt(Map<String, String> props, String key, IntConsumer consumer) {
        String value = props.get(key);
        if (value == null) {
            return;
        }
        try {
            consumer.accept(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyDataSize(Map<String, String> props, String key, LongConsumer consumer) {
        String value = props.get(key);
        if (value == null) {
            return;
        }
        try {
            consumer.accept(DataSizeParser.parseToBytes(value));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void applyDouble(Map<String, String> props, String key, DoubleConsumer consumer) {
        String value = props.get(key);
        if (value == null) {
            return;
        }
        try {
            consumer.accept(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyString(Map<String, String> props, String key, StringConsumer consumer) {
        String value = props.get(key);
        if (value != null) {
            consumer.accept(value);
        }
    }

    private void applyCsv(Map<String, String> props, String key, java.util.function.Consumer<List<String>> consumer) {
        String value = props.get(key);
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String[] parts = value.split(",");
        List<String> values = new ArrayList<String>(parts.length);
        for (String part : parts) {
            String trimmed = part == null ? null : part.trim();
            if (trimmed != null && !trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        if (!values.isEmpty()) {
            consumer.accept(values);
        }
    }

    private <E extends Enum<E>> void applyEnum(Map<String, String> props,
                                               String key,
                                               Class<E> enumClass,
                                               EnumConsumer<E> consumer) {
        String value = props.get(key);
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        try {
            consumer.accept(Enum.valueOf(enumClass, value.trim().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
        }
    }

}
