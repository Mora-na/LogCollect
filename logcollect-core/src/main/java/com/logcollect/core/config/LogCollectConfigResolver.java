package com.logcollect.core.config;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.internal.LogCollectInternalLogger;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LogCollectConfigResolver {
    private static final String PREFIX = "logcollect.";

    private final List<LogCollectConfigSource> sources;
    private final LogCollectLocalConfigCache cache;
    private final ConcurrentHashMap<String, String> configCache = new ConcurrentHashMap<String, String>();
    private volatile Instant lastRefreshTime;

    public LogCollectConfigResolver(List<LogCollectConfigSource> sources, LogCollectLocalConfigCache cache) {
        this.sources = sources == null ? Collections.<LogCollectConfigSource>emptyList() : sources;
        this.cache = cache;
    }

    public LogCollectConfig resolve(Method method, LogCollect annotation) {
        LogCollectConfig config = new LogCollectConfig();
        Map<String, String> props = loadProperties();
        applyProperties(config, props);
        if (annotation != null) {
            config.setAsync(annotation.async());
            config.setLevel(annotation.level());
            config.setUseBuffer(annotation.useBuffer());
            config.setMaxBufferSize(annotation.maxBufferSize());
            config.setMaxBufferBytes(annotation.maxBufferBytes());
            config.setDegradeFileTTLDays(annotation.degradeFileTTLDays());
            config.setHandlerTimeoutMs(annotation.handlerTimeoutMs());
            config.setTransactionIsolation(annotation.transactionIsolation());
            config.setMaxNestingDepth(annotation.maxNestingDepth());
        }
        return config;
    }

    private Map<String, String> loadProperties() {
        if (!configCache.isEmpty()) {
            return new HashMap<String, String>(configCache);
        }
        Map<String, String> loaded = loadPropertiesInternal();
        if (loaded == null) {
            loaded = Collections.<String, String>emptyMap();
        }
        if (!loaded.isEmpty()) {
            configCache.putAll(loaded);
        }
        lastRefreshTime = Instant.now();
        return loaded;
    }

    private Map<String, String> loadPropertiesInternal() {
        if (sources.isEmpty()) {
            return cache != null ? cache.load() : Collections.<String, String>emptyMap();
        }
        List<LogCollectConfigSource> ordered = new ArrayList<LogCollectConfigSource>(sources);
        Collections.sort(ordered, new Comparator<LogCollectConfigSource>() {
            @Override
            public int compare(LogCollectConfigSource o1, LogCollectConfigSource o2) {
                return Integer.compare(o1.getOrder(), o2.getOrder());
            }
        });
        Map<String, String> result = new HashMap<String, String>();
        for (LogCollectConfigSource source : ordered) {
            try {
                if (!source.isAvailable()) {
                    continue;
                }
                Map<String, String> props = source.getProperties(PREFIX);
                if (props != null) {
                    result.putAll(props);
                }
            } catch (Throwable t) {
                LogCollectInternalLogger.warn("Config source failed: {}", source.getType(), t);
            }
        }
        if (!result.isEmpty() && cache != null) {
            cache.save(result);
        }
        if (result.isEmpty() && cache != null) {
            return cache.load();
        }
        return result;
    }

    private void applyProperties(LogCollectConfig config, Map<String, String> props) {
        if (props == null || props.isEmpty()) {
            return;
        }
        applyBoolean(props, "logcollect.enabled", config::setEnabled);
        applyBoolean(props, "logcollect.global.async", config::setAsync);
        applyString(props, "logcollect.global.level", config::setLevel);
        applyInt(props, "logcollect.global.handler-timeout-ms", config::setHandlerTimeoutMs);
        applyInt(props, "logcollect.global.max-nesting-depth", config::setMaxNestingDepth);
        applyInt(props, "logcollect.global.degrade.window-seconds", config::setDegradeWindowSeconds);
        applyInt(props, "logcollect.global.failure-threshold", config::setFailureThreshold);
        applyInt(props, "logcollect.global.half-open-success-threshold", config::setHalfOpenSuccessThreshold);
        applyInt(props, "logcollect.global.half-open-pass-count", config::setHalfOpenPassCount);
        applyInt(props, "logcollect.global.recover-interval-seconds", config::setRecoverIntervalSeconds);
        applyInt(props, "logcollect.global.max-recover-interval-seconds", config::setMaxRecoverIntervalSeconds);
        String totalBytes = props.get("logcollect.global.buffer.total-max-bytes");
        if (totalBytes != null) {
            config.setGlobalBufferTotalMaxBytes(parseBytes(totalBytes));
        }
    }

    private void applyBoolean(Map<String, String> props, String key, BooleanConsumer consumer) {
        String val = props.get(key);
        if (val != null) {
            consumer.accept(Boolean.parseBoolean(val));
        }
    }

    private void applyInt(Map<String, String> props, String key, IntConsumer consumer) {
        String val = props.get(key);
        if (val != null) {
            try {
                consumer.accept(Integer.parseInt(val));
            } catch (NumberFormatException ignore) {
            }
        }
    }

    private void applyString(Map<String, String> props, String key, StringConsumer consumer) {
        String val = props.get(key);
        if (val != null) {
            consumer.accept(val);
        }
    }

    private long parseBytes(String raw) {
        String v = raw.trim().toUpperCase();
        long factor = 1;
        if (v.endsWith("KB")) {
            factor = 1024L;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("MB")) {
            factor = 1024L * 1024;
            v = v.substring(0, v.length() - 2);
        } else if (v.endsWith("GB")) {
            factor = 1024L * 1024 * 1024;
            v = v.substring(0, v.length() - 2);
        }
        try {
            return Long.parseLong(v) * factor;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int clearCache() {
        int size = configCache.size();
        configCache.clear();
        lastRefreshTime = Instant.now();
        return size;
    }

    public int getCacheSize() {
        return configCache.size();
    }

    public String getLastRefreshTimeFormatted() {
        return lastRefreshTime != null ? lastRefreshTime.toString() : "never";
    }

    public void saveToLocalCache() {
        if (cache == null || sources.isEmpty()) {
            return;
        }
        Map<String, String> allProps = new HashMap<String, String>();
        for (LogCollectConfigSource source : sources) {
            if (!source.isAvailable()) {
                continue;
            }
            try {
                Map<String, String> props = source.getProperties(PREFIX);
                if (props != null) {
                    allProps.putAll(props);
                }
            } catch (Throwable t) {
                LogCollectInternalLogger.warn("Config source failed: {}", source.getType(), t);
            }
        }
        if (!allProps.isEmpty()) {
            cache.save(allProps);
        }
    }

    private interface BooleanConsumer { void accept(boolean v); }
    private interface IntConsumer { void accept(int v); }
    private interface StringConsumer { void accept(String v); }
}
