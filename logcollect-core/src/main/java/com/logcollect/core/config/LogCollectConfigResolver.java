package com.logcollect.core.config;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.enums.LogFramework;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import com.logcollect.core.util.DataSizeParser;
import com.logcollect.core.util.MethodKeyResolver;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LogCollectConfigResolver {

    private static final String GLOBAL_PREFIX = "logcollect.global.";
    private static final String METHODS_PREFIX = "logcollect.methods.";

    private final List<LogCollectConfigSource> sources;
    private final LogCollectLocalConfigCache cache;
    private final LogCollectGlobalSwitch globalSwitch;
    private final Object metrics;

    private final ConcurrentHashMap<String, LogCollectConfig> resolvedCache =
            new ConcurrentHashMap<String, LogCollectConfig>();
    private volatile Instant lastRefreshTime;

    public LogCollectConfigResolver(List<LogCollectConfigSource> sources, LogCollectLocalConfigCache cache) {
        this(sources, cache, null, null);
    }

    public LogCollectConfigResolver(List<LogCollectConfigSource> sources,
                                    LogCollectLocalConfigCache cache,
                                    LogCollectGlobalSwitch globalSwitch,
                                    Object metrics) {
        List<LogCollectConfigSource> sorted = new ArrayList<LogCollectConfigSource>();
        if (sources != null) {
            sorted.addAll(sources);
        }
        sorted.sort(Comparator.comparingInt(LogCollectConfigSource::getOrder));
        this.sources = Collections.unmodifiableList(sorted);
        this.cache = cache;
        this.globalSwitch = globalSwitch;
        this.metrics = metrics;
    }

    /**
     * 四级合并：
     * ④ 框架默认 <- ③ 注解显式 <- ② 配置中心方法级 <- ① 配置中心全局
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

        // 第②级：方法级
        Map<String, String> methodProperties = loadMethodProperties(configMethodKey);
        mergeFromProperties(config, methodProperties);

        // 第①级：全局（最高优先）
        Map<String, String> globalProperties = loadGlobalProperties();
        mergeFromProperties(config, globalProperties);
        return config;
    }

    public void onConfigChange() {
        onConfigChange("unknown");
    }

    public void onConfigChange(String source) {
        clearCache();
        syncGlobalEnabledFromSources();
        invokeMetrics("incrementConfigRefresh", source == null ? "unknown" : source);
        LogCollectInternalLogger.info("Config cache cleared due to config change from: {}",
                source == null ? "unknown" : source);
    }

    public int clearCache() {
        int size = resolvedCache.size();
        resolvedCache.clear();
        lastRefreshTime = Instant.now();
        return size;
    }

    public int getCacheSize() {
        return resolvedCache.size();
    }

    public String getLastRefreshTimeFormatted() {
        return lastRefreshTime != null ? lastRefreshTime.toString() : "never";
    }

    public Instant getLastRefreshTime() {
        return lastRefreshTime;
    }

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

    public Map<String, LogCollectConfig> getAllCachedConfigs() {
        return Collections.unmodifiableMap(resolvedCache);
    }

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
            } catch (Throwable t) {
                LogCollectInternalLogger.warn("Load global properties failed from {}", source.getType(), t);
            }
        }
        if (!merged.isEmpty()) {
            return merged;
        }
        return loadGlobalPropertiesFromLocalCache();
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
            } catch (Throwable t) {
                LogCollectInternalLogger.warn("Load method properties failed from {}", source.getType(), t);
            }
        }
        if (!merged.isEmpty()) {
            return merged;
        }
        return loadMethodPropertiesFromLocalCache(configMethodKey);
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
            } catch (Throwable t) {
                LogCollectInternalLogger.warn("Load config source failed: {}", source.getType(), t);
            }
        }
        return result;
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
        if (!"INFO".equals(annotation.level())) {
            config.setLevel(annotation.level());
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
        if (annotation.blockWhenDegradeFail()) {
            config.setBlockWhenDegradeFail(true);
        }

        if (!annotation.enableSanitize()) {
            config.setEnableSanitize(false);
        }
        if (annotation.sanitizer() != com.logcollect.api.security.DefaultLogSanitizer.class) {
            config.setSanitizerClass(annotation.sanitizer());
        }
        if (!annotation.enableMask()) {
            config.setEnableMask(false);
        }
        if (annotation.masker() != com.logcollect.api.security.DefaultLogMasker.class) {
            config.setMaskerClass(annotation.masker());
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
        applyEnum(props, "log-framework", LogFramework.class, config::setLogFramework);
        applyEnum(props, "collect-mode", CollectMode.class, config::setCollectMode);

        applyBoolean(props, "buffer.enabled", config::setUseBuffer);
        applyInt(props, "buffer.max-size", config::setMaxBufferSize);
        applyDataSize(props, "buffer.max-bytes", config::setMaxBufferBytes);
        applyDataSize(props, "buffer.total-max-bytes", config::setGlobalBufferTotalMaxBytes);

        applyBoolean(props, "degrade.enabled", config::setEnableDegrade);
        applyInt(props, "degrade.fail-threshold", config::setDegradeFailThreshold);
        applyEnum(props, "degrade.storage", DegradeStorage.class, config::setDegradeStorage);
        applyInt(props, "degrade.recover-interval-seconds", config::setRecoverIntervalSeconds);
        applyInt(props, "degrade.recover-max-interval-seconds", config::setRecoverMaxIntervalSeconds);
        applyInt(props, "degrade.half-open-pass-count", config::setHalfOpenPassCount);
        applyInt(props, "degrade.half-open-success-threshold", config::setHalfOpenSuccessThreshold);
        applyBoolean(props, "degrade.block-when-degrade-fail", config::setBlockWhenDegradeFail);

        applyString(props, "degrade.file.max-total-size", config::setDegradeFileMaxTotalSize);
        applyInt(props, "degrade.file.ttl-days", config::setDegradeFileTTLDays);
        applyBoolean(props, "degrade.file.encrypt-enabled", config::setEnableDegradeFileEncrypt);

        applyBoolean(props, "security.sanitize.enabled", config::setEnableSanitize);
        applyBoolean(props, "security.mask.enabled", config::setEnableMask);

        applyInt(props, "handler-timeout-ms", config::setHandlerTimeoutMs);
        applyBoolean(props, "transaction-isolation", config::setTransactionIsolation);
        applyInt(props, "max-nesting-depth", config::setMaxNestingDepth);

        applyBoolean(props, "metrics.enabled", config::setEnableMetrics);

        // 兼容旧 key
        applyBoolean(props, "logcollect.enabled", config::setEnabled);
        applyInt(props, "failure-threshold", config::setDegradeFailThreshold);
        applyInt(props, "half-open-success-threshold", config::setHalfOpenSuccessThreshold);
        applyInt(props, "half-open-pass-count", config::setHalfOpenPassCount);
        applyInt(props, "recover-interval-seconds", config::setRecoverIntervalSeconds);
        applyInt(props, "max-recover-interval-seconds", config::setRecoverMaxIntervalSeconds);
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
            } catch (Throwable t) {
                LogCollectInternalLogger.warn("Sync global enabled from {} failed", source.getType(), t);
            }
        }
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

    private void applyString(Map<String, String> props, String key, StringConsumer consumer) {
        String value = props.get(key);
        if (value != null) {
            consumer.accept(value);
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

    private void invokeMetrics(String methodName, Object... args) {
        if (metrics == null) {
            return;
        }
        try {
            MethodLoop:
            for (java.lang.reflect.Method method : metrics.getClass().getMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterTypes().length != args.length) {
                    continue;
                }
                Class<?>[] paramTypes = method.getParameterTypes();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (args[i] == null) {
                        continue;
                    }
                    if (!wrap(paramTypes[i]).isAssignableFrom(wrap(args[i].getClass()))) {
                        continue MethodLoop;
                    }
                }
                method.invoke(metrics, args);
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        return type;
    }
}
