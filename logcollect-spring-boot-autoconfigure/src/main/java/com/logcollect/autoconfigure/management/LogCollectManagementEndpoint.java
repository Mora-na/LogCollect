package com.logcollect.autoconfigure.management;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.degrade.DegradeFileManager;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import com.logcollect.core.util.MethodKeyResolver;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private final LogCollectGlobalSwitch globalSwitch;
    private final LogCollectMetrics metrics;
    private final LogCollectManagementAuditLogger auditLogger;

    private final AtomicReference<Instant> lastRefreshTime = new AtomicReference<Instant>(Instant.EPOCH);
    private final AtomicLong lastForceCleanupTime = new AtomicLong(0);

    public LogCollectManagementEndpoint(
            CircuitBreakerRegistry circuitBreakerRegistry,
            LogCollectConfigResolver configResolver,
            @Autowired(required = false) List<LogCollectConfigSource> configSources,
            @Autowired(required = false) DegradeFileManager degradeFileManager,
            @Autowired(required = false) GlobalBufferMemoryManager bufferMemoryManager,
            LogCollectGlobalSwitch globalSwitch,
            @Autowired(required = false) LogCollectMetrics metrics,
            @Autowired(required = false) LogCollectManagementAuditLogger auditLogger) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.configResolver = configResolver;
        this.configSources = configSources != null ? configSources : Collections.<LogCollectConfigSource>emptyList();
        this.degradeFileManager = degradeFileManager;
        this.bufferMemoryManager = bufferMemoryManager;
        this.globalSwitch = globalSwitch;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        result.put("enabled", globalSwitch != null && globalSwitch.isEnabled());

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
                info.put("collectMode", cfg.getCollectMode() == null ? "AUTO" : cfg.getCollectMode().name());
                info.put("effectiveCollectMode",
                        cfg.getEffectiveCollectMode() == null ? "N/A" : cfg.getEffectiveCollectMode().name());
                info.put("level", cfg.getLevel());
                info.put("async", cfg.isAsync());
                info.put("enabled", cfg.isEnabled());
            }
            registeredMethods.put(methodKey, info);
        }
        result.put("registeredMethods", registeredMethods);

        if (bufferMemoryManager != null) {
            Map<String, Object> buffer = new LinkedHashMap<String, Object>();
            long used = bufferMemoryManager.getCurrentUsedBytes();
            long soft = bufferMemoryManager.getMaxTotalBytes();
            long hard = bufferMemoryManager.getHardCeilingBytes();
            buffer.put("totalUsedBytes", used);
            buffer.put("totalUsedHuman", formatBytes(used));
            buffer.put("softLimitBytes", soft);
            buffer.put("softLimitHuman", formatBytes(soft));
            buffer.put("hardCeilingBytes", hard);
            buffer.put("hardCeilingHuman", formatBytes(hard));
            buffer.put("softUtilization", String.format("%.2f%%", bufferMemoryManager.utilization() * 100));
            buffer.put("hardUtilization", String.format("%.2f%%", bufferMemoryManager.hardCeilingUtilization() * 100));
            buffer.put("counterMode", bufferMemoryManager.getCounterMode().name());
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
        if (auditLogger != null) {
            result.put("managementAuditFile", auditLogger.getAuditFilePath());
        }

        if (metrics != null) {
            result.put("activeCollections", metrics.getActiveCollections());
            result.put("totalCollected", metrics.getTotalCollected());
            result.put("totalPersisted", metrics.getTotalPersisted());
            result.put("totalDiscarded", metrics.getTotalDiscarded());
            result.put("totalFlushes", metrics.getTotalFlushes());
            result.put("sanitizeHits", metrics.getTotalSanitizeHits());
            result.put("maskHits", metrics.getTotalMaskHits());
            result.put("fastPathHits", metrics.getTotalFastPathHits());
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
            String normalized = MethodKeyResolver.normalize(methodKey);
            LogCollectCircuitBreaker breaker = circuitBreakerRegistry.get(normalized);

            if (breaker == null) {
                result.put("error", "Method not found: " + methodKey);
                result.put("hint", "Try display format: com.example.Class#method");
                result.put("registeredMethods", circuitBreakerRegistry.getAllMethodKeys());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
            }

            LogCollectCircuitBreaker.State previousState = breaker.getState();
            breaker.manualReset();

            result.put("method", normalized);
            result.put("previousState", previousState.name());
            result.put("currentState", breaker.getState().name());
            result.put("resetTime", Instant.now().toString());

            LogCollectInternalLogger.info("Circuit breaker manually reset: method={}, previousState={}",
                    normalized, previousState);
            audit("circuitBreakerReset", true, "method=" + normalized + ", previous=" + previousState.name());

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
        audit("circuitBreakerReset", true, "method=ALL,count=" + resetDetails.size());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/refreshConfig")
    public ResponseEntity<Map<String, Object>> refreshConfig() {
        Instant now = Instant.now();
        Instant last = lastRefreshTime.get();
        if (Duration.between(last, now).toMillis() < REFRESH_MIN_INTERVAL_MS
                || !lastRefreshTime.compareAndSet(last, now)) {
            long waitSeconds = Math.max(1L,
                    (REFRESH_MIN_INTERVAL_MS - Duration.between(last, now).toMillis()) / 1000 + 1);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("error", "Too frequent, please wait " + waitSeconds + " seconds");
            audit("refreshConfig", false, "too_frequent");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result);
        }

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
            } catch (Exception t) {
                sr.put("status", "error");
                sr.put("error", t.getMessage());
                LogCollectInternalLogger.warn("Config source refresh failed: {}", source.getType(), t);
            } catch (Error e) {
                throw e;
            }
            sourceResults.add(sr);
        }
        result.put("configSources", sourceResults);

        int clearedCount = configResolver.clearCache();
        result.put("cacheClearedCount", clearedCount);
        configResolver.onConfigChange("management");

        try {
            configResolver.saveToLocalCache();
            result.put("localCacheSaved", true);
        } catch (Exception t) {
            result.put("localCacheSaved", false);
            result.put("localCacheError", t.getMessage());
        } catch (Error e) {
            throw e;
        }

        result.put("refreshTime", Instant.now().toString());

        LogCollectInternalLogger.info("Config manually refreshed, cacheClearedCount={}", clearedCount);
        audit("refreshConfig", true, "cacheCleared=" + clearedCount);

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
                audit("cleanupDegradeFiles", true, "force=true,deleted=" + cleanedCount);
            } else {
                DegradeFileManager.CleanupResult cleanup = degradeFileManager.cleanExpiredFiles();
                cleanedCount = cleanup.getDeletedCount();
                cleanedBytes = cleanup.getDeletedBytes();
                result.put("mode", "ttl (expired only, ttlDays=" + degradeFileManager.getTtlDays() + ")");

                LogCollectInternalLogger.info("TTL cleanup degrade files: deleted={}, freedBytes={}",
                        cleanedCount, cleanedBytes);
                audit("cleanupDegradeFiles", true, "force=false,deleted=" + cleanedCount);
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

        } catch (Exception t) {
            result.put("error", "Cleanup failed: " + t.getMessage());
            LogCollectInternalLogger.error("Degrade file cleanup failed", t);
            audit("cleanupDegradeFiles", false, "error=" + t.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        } catch (Error e) {
            throw e;
        }

        return ResponseEntity.ok(result);
    }

    @PutMapping("/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(@RequestParam boolean value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        boolean previous = globalSwitch == null ? value : globalSwitch.setEnabled(value);
        result.put("previousEnabled", previous);
        result.put("currentEnabled", value);
        result.put("updateTime", Instant.now().toString());

        if (previous != value) {
            if (!value) {
                LogCollectInternalLogger.warn("LogCollect globally DISABLED via management endpoint");
            } else {
                LogCollectInternalLogger.info("LogCollect globally ENABLED via management endpoint");
            }
            audit("setEnabled", true, "value=" + value);
        } else {
            result.put("message", "No change, already " + (value ? "enabled" : "disabled"));
            audit("setEnabled", true, "noop,value=" + value);
        }

        return ResponseEntity.ok(result);
    }

    private void audit(String action, boolean success, String detail) {
        if (auditLogger == null) {
            return;
        }
        auditLogger.audit(action, null, null, success, detail);
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
