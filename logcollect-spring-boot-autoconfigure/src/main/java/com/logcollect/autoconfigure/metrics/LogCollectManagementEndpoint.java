package com.logcollect.autoconfigure.metrics;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.degrade.DegradeFileManager;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@RestControllerEndpoint(id = "logcollect")
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@ConditionalOnProperty(prefix = "logcollect.management", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogCollectManagementEndpoint {

    private static final long REFRESH_MIN_INTERVAL_MS = 10_000L;
    private static final long FORCE_CLEANUP_MIN_INTERVAL_MS = 60_000L;

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final LogCollectConfigResolver configResolver;
    private final List<LogCollectConfigSource> configSources;
    private final DegradeFileManager degradeFileManager;
    private final GlobalBufferMemoryManager bufferMemoryManager;
    private final AtomicBoolean globalEnabled;
    private final LogCollectMetrics metrics;

    private final AtomicLong lastRefreshTime = new AtomicLong(0);
    private final AtomicLong lastForceCleanupTime = new AtomicLong(0);

    public LogCollectManagementEndpoint(
            CircuitBreakerRegistry circuitBreakerRegistry,
            LogCollectConfigResolver configResolver,
            @Autowired(required = false) List<LogCollectConfigSource> configSources,
            @Autowired(required = false) DegradeFileManager degradeFileManager,
            @Autowired(required = false) GlobalBufferMemoryManager bufferMemoryManager,
            @Qualifier("logCollectGlobalEnabled") AtomicBoolean globalEnabled,
            @Autowired(required = false) LogCollectMetrics metrics) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.configResolver = configResolver;
        this.configSources = configSources != null ? configSources : Collections.<LogCollectConfigSource>emptyList();
        this.degradeFileManager = degradeFileManager;
        this.bufferMemoryManager = bufferMemoryManager;
        this.globalEnabled = globalEnabled;
        this.metrics = metrics;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        result.put("enabled", globalEnabled.get());

        Map<String, Object> breakers = new LinkedHashMap<String, Object>();
        circuitBreakerRegistry.getAll().forEach((method, breaker) -> {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("state", breaker.getState().name());
            detail.put("consecutiveFailures", breaker.getConsecutiveFailures());
            detail.put("lastFailureTime", breaker.getLastFailureTimeFormatted());
            detail.put("currentRecoverIntervalMs", breaker.getCurrentRecoverInterval());
            breakers.put(method, detail);
        });
        result.put("circuitBreakers", breakers);

        Map<String, Object> registeredMethods = new LinkedHashMap<String, Object>();
        for (String methodKey : circuitBreakerRegistry.getAllMethodKeys()) {
            Map<String, Object> info = new LinkedHashMap<String, Object>();
            LogCollectConfig cfg = configResolver.getCachedConfig(methodKey);
            if (cfg != null) {
                info.put("collectMode", cfg.getCollectMode().name());
                info.put("level", cfg.getLevel());
                info.put("async", cfg.isAsync());
                info.put("enabled", cfg.isEnabled());
            }
            registeredMethods.put(methodKey, info);
        }
        result.put("registeredMethods", registeredMethods);

        if (bufferMemoryManager != null) {
            Map<String, Object> buffer = new LinkedHashMap<String, Object>();
            buffer.put("totalUsedBytes", bufferMemoryManager.getTotalUsed());
            buffer.put("maxTotalBytes", bufferMemoryManager.getMaxTotalBytes());
            buffer.put("utilization", String.format("%.2f%%", bufferMemoryManager.utilization() * 100));
            result.put("globalBuffer", buffer);
        }

        if (degradeFileManager != null) {
            Map<String, Object> files = new LinkedHashMap<String, Object>();
            files.put("fileCount", degradeFileManager.getFileCount());
            files.put("totalSizeBytes", degradeFileManager.getTotalSizeBytes());
            files.put("totalSizeHuman", degradeFileManager.getTotalSizeHuman());
            files.put("maxTotalSizeBytes", degradeFileManager.getMaxTotalBytes());
            files.put("ttlDays", degradeFileManager.getTtlDays());
            files.put("diskFreeSpaceBytes", degradeFileManager.getDiskFreeSpace());
            files.put("diskFreeSpaceHuman", degradeFileManager.getDiskFreeSpaceHuman());
            files.put("baseDir", degradeFileManager.getBaseDir() == null ? null : degradeFileManager.getBaseDir().toString());
            result.put("degradeFiles", files);
        }

        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        for (LogCollectConfigSource source : configSources) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("type", source.getType());
            s.put("order", source.getOrder());
            s.put("available", source.isAvailable());
            sources.add(s);
        }
        result.put("configSources", sources);

        result.put("configCacheSize", configResolver.getCacheSize());
        result.put("lastConfigRefreshTime", configResolver.getLastRefreshTimeFormatted());

        if (metrics != null) {
            result.put("activeCollections", metrics.getActiveCollections());
            result.put("totalCollected", metrics.getTotalCollected());
            result.put("totalPersisted", metrics.getTotalPersisted());
            result.put("totalDiscarded", metrics.getTotalDiscarded());
            result.put("totalFlushes", metrics.getTotalFlushes());
            result.put("sanitizeHits", metrics.getTotalSanitizeHits());
            result.put("maskHits", metrics.getTotalMaskHits());
        }

        result.put("logFramework", detectLogFramework());
        result.put("contextPropagation", isContextPropagationAvailable());
        result.put("springBootVersion", SpringBootVersion.getVersion());

        return result;
    }

    @PostMapping("/circuitBreakerReset")
    public ResponseEntity<Map<String, Object>> circuitBreakerReset(
            @RequestParam(required = false) String method) {

        Map<String, Object> result = new LinkedHashMap<String, Object>();

        if (method != null && !method.trim().isEmpty()) {
            String methodKey = method.trim();
            LogCollectCircuitBreaker breaker = circuitBreakerRegistry.get(methodKey);

            if (breaker == null) {
                result.put("error", "Method not found: " + methodKey);
                result.put("registeredMethods", circuitBreakerRegistry.getAllMethodKeys());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
            }

            LogCollectCircuitBreaker.State previousState = breaker.getState();
            breaker.manualReset();

            result.put("method", methodKey);
            result.put("previousState", previousState.name());
            result.put("currentState", breaker.getState().name());
            result.put("resetTime", Instant.now().toString());

            LogCollectInternalLogger.info("Circuit breaker manually reset: method={}, previousState={}",
                    methodKey, previousState);

            return ResponseEntity.ok(result);
        }

        Map<String, LogCollectCircuitBreaker> all = circuitBreakerRegistry.getAll();
        if (all.isEmpty()) {
            result.put("message", "No circuit breakers registered");
            return ResponseEntity.ok(result);
        }

        List<Map<String, Object>> resetDetails = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, LogCollectCircuitBreaker> entry : all.entrySet()) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("method", entry.getKey());
            detail.put("previousState", entry.getValue().getState().name());
            entry.getValue().manualReset();
            detail.put("currentState", entry.getValue().getState().name());
            resetDetails.add(detail);
        }

        result.put("resetCount", resetDetails.size());
        result.put("details", resetDetails);
        result.put("resetTime", Instant.now().toString());

        LogCollectInternalLogger.info("All circuit breakers manually reset, count={}", resetDetails.size());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/refreshConfig")
    public ResponseEntity<Map<String, Object>> refreshConfig() {
        long now = System.currentTimeMillis();
        long last = lastRefreshTime.get();
        if (now - last < REFRESH_MIN_INTERVAL_MS) {
            long waitSeconds = (REFRESH_MIN_INTERVAL_MS - (now - last)) / 1000 + 1;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("error", "Too frequent, please wait " + waitSeconds + " seconds");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result);
        }
        lastRefreshTime.set(now);

        Map<String, Object> result = new LinkedHashMap<String, Object>();

        List<Map<String, Object>> sourceResults = new ArrayList<Map<String, Object>>();
        for (LogCollectConfigSource source : configSources) {
            Map<String, Object> sr = new LinkedHashMap<String, Object>();
            sr.put("type", source.getType());
            sr.put("order", source.getOrder());

            try {
                if (source.isAvailable()) {
                    source.refresh();
                    sr.put("status", "refreshed");
                } else {
                    sr.put("status", "unavailable");
                }
            } catch (Throwable t) {
                sr.put("status", "error");
                sr.put("error", t.getMessage());
                LogCollectInternalLogger.warn("Config source refresh failed: {}", source.getType(), t);
            }
            sourceResults.add(sr);
        }
        result.put("configSources", sourceResults);

        int clearedCount = configResolver.clearCache();
        result.put("cacheClearedCount", clearedCount);

        try {
            configResolver.saveToLocalCache();
            result.put("localCacheSaved", true);
        } catch (Throwable t) {
            result.put("localCacheSaved", false);
            result.put("localCacheError", t.getMessage());
        }

        result.put("refreshTime", Instant.now().toString());

        LogCollectInternalLogger.info("Config manually refreshed, cacheClearedCount={}", clearedCount);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/cleanupDegradeFiles")
    public ResponseEntity<Map<String, Object>> cleanupDegradeFiles(
            @RequestParam(required = false, defaultValue = "false") boolean force) {

        Map<String, Object> result = new LinkedHashMap<String, Object>();

        if (degradeFileManager == null) {
            result.put("error", "DegradeFileManager not initialized (degradeStorage may not be FILE)");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
        }

        if (force) {
            long now = System.currentTimeMillis();
            long last = lastForceCleanupTime.get();
            if (now - last < FORCE_CLEANUP_MIN_INTERVAL_MS) {
                long waitSeconds = (FORCE_CLEANUP_MIN_INTERVAL_MS - (now - last)) / 1000 + 1;
                result.put("error", "Too frequent, please wait " + waitSeconds + " seconds");
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result);
            }
            lastForceCleanupTime.set(now);
        }

        long beforeCount = degradeFileManager.getFileCount();
        long beforeBytes = degradeFileManager.getTotalSizeBytes();

        try {
            int cleanedCount;
            long cleanedBytes;

            if (force) {
                DegradeFileManager.CleanupResult cleanup = degradeFileManager.cleanAllFiles();
                cleanedCount = cleanup.getDeletedCount();
                cleanedBytes = cleanup.getDeletedBytes();
                result.put("mode", "force (all files)");

                LogCollectInternalLogger.warn("Force cleanup all degrade files: deleted={}, freedBytes={}",
                        cleanedCount, cleanedBytes);
            } else {
                DegradeFileManager.CleanupResult cleanup = degradeFileManager.cleanExpiredFiles();
                cleanedCount = cleanup.getDeletedCount();
                cleanedBytes = cleanup.getDeletedBytes();
                result.put("mode", "ttl (expired only, ttlDays=" + degradeFileManager.getTtlDays() + ")");

                LogCollectInternalLogger.info("TTL cleanup degrade files: deleted={}, freedBytes={}",
                        cleanedCount, cleanedBytes);
            }

            result.put("before", buildFileStats(beforeCount, beforeBytes));
            Map<String, Object> cleaned = new LinkedHashMap<String, Object>();
            cleaned.put("fileCount", cleanedCount);
            cleaned.put("freedBytes", cleanedBytes);
            cleaned.put("freedHuman", formatBytes(cleanedBytes));
            result.put("cleaned", cleaned);
            result.put("after", buildFileStats(
                    degradeFileManager.getFileCount(),
                    degradeFileManager.getTotalSizeBytes()));
            result.put("diskFreeSpaceAfter", degradeFileManager.getDiskFreeSpaceHuman());
            result.put("cleanupTime", Instant.now().toString());

        } catch (Throwable t) {
            result.put("error", "Cleanup failed: " + t.getMessage());
            LogCollectInternalLogger.error("Degrade file cleanup failed", t);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }

        return ResponseEntity.ok(result);
    }

    @PutMapping("/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(@RequestParam boolean value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        boolean previous = globalEnabled.getAndSet(value);
        result.put("previousEnabled", previous);
        result.put("currentEnabled", value);
        result.put("updateTime", Instant.now().toString());

        if (previous != value) {
            if (!value) {
                LogCollectInternalLogger.warn("LogCollect globally DISABLED via management endpoint");
            } else {
                LogCollectInternalLogger.info("LogCollect globally ENABLED via management endpoint");
            }
        } else {
            result.put("message", "No change, already " + (value ? "enabled" : "disabled"));
        }

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildFileStats(long count, long bytes) {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("fileCount", count);
        stats.put("totalBytes", bytes);
        stats.put("totalHuman", formatBytes(bytes));
        return stats;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String detectLogFramework() {
        try {
            Class.forName("ch.qos.logback.classic.LoggerContext");
            return "Logback";
        } catch (ClassNotFoundException ignored) {
        }
        try {
            Class.forName("org.apache.logging.log4j.core.LoggerContext");
            return "Log4j2";
        } catch (ClassNotFoundException ignored) {
        }
        return "Unknown";
    }

    private boolean isContextPropagationAvailable() {
        try {
            Class.forName("io.micrometer.context.ContextRegistry");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
