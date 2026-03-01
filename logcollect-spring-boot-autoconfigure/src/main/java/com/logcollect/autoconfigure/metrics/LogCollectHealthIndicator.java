package com.logcollect.autoconfigure.metrics;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.autoconfigure.LogCollectProperties;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.degrade.DegradeFileManager;
import com.logcollect.core.util.DataSizeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LogCollectHealthIndicator implements HealthIndicator {

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired(required = false)
    private LogCollectMetrics metrics;

    @Autowired(required = false)
    private DegradeFileManager degradeFileManager;

    @Autowired(required = false)
    private LogCollectProperties properties;

    @Autowired(required = false)
    private List<LogCollectConfigSource> configSources;

    @Override
    public Health health() {
        Map<String, LogCollectCircuitBreaker> breakers =
                circuitBreakerRegistry == null ? java.util.Collections.emptyMap() : circuitBreakerRegistry.getAll();

        boolean hasOpen = false;
        boolean hasHalfOpen = false;
        Map<String, String> states = new LinkedHashMap<String, String>();
        for (Map.Entry<String, LogCollectCircuitBreaker> entry : breakers.entrySet()) {
            LogCollectCircuitBreaker.State state = entry.getValue().getState();
            states.put(entry.getKey(), state.name());
            if (state == LogCollectCircuitBreaker.State.OPEN) {
                hasOpen = true;
            }
            if (state == LogCollectCircuitBreaker.State.HALF_OPEN) {
                hasHalfOpen = true;
            }
        }

        Health.Builder builder;
        if (hasOpen) {
            builder = Health.down();
        } else if (hasHalfOpen) {
            builder = Health.status("DEGRADED");
        } else {
            builder = Health.up();
        }

        builder.withDetail("circuitBreakers", states);

        if (metrics != null) {
            builder.withDetail("activeCollections", metrics.getActiveCollections());
            builder.withDetail("globalBufferUtilization",
                    String.format("%.2f%%", metrics.getGlobalBufferUtilization() * 100));
        }

        if (degradeFileManager != null) {
            builder.withDetail("degradeFileCount", degradeFileManager.getFileCount());
            builder.withDetail("degradeFileTotalSize", DataSizeParser.formatBytes(degradeFileManager.getTotalSizeBytes()));
            builder.withDetail("degradeFileDiskFreeSpace", DataSizeParser.formatBytes(degradeFileManager.getDiskFreeSpace()));
        }

        if (configSources != null) {
            List<String> sources = configSources.stream()
                    .map(s -> s.getClass().getSimpleName() + "(order=" + s.getOrder() + ",available=" + s.isAvailable() + ")")
                    .collect(Collectors.toList());
            builder.withDetail("configSources", sources);
        }

        builder.withDetail("enabled", properties == null || properties.getGlobal().isEnabled());
        builder.withDetail("logFramework", detectLogFramework());
        builder.withDetail("contextPropagation", isContextPropagationAvailable());
        builder.withDetail("springBootVersion", SpringBootVersion.getVersion());
        return builder.build();
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
