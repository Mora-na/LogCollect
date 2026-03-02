package com.logcollect.autoconfigure.metrics;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.degrade.DegradeFileManager;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogCollectHealthIndicatorTest {

    @Test
    void health_withoutDependencies_reportsUp() {
        LogCollectHealthIndicator indicator = new LogCollectHealthIndicator();

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsKeys(
                "enabled", "circuitBreakers", "logFramework", "contextPropagation", "springBootVersion");
    }

    @Test
    void health_halfOpenBreaker_reportsDegraded() {
        LogCollectHealthIndicator indicator = new LogCollectHealthIndicator();

        CircuitBreakerRegistry registry = new CircuitBreakerRegistry();
        LogCollectCircuitBreaker halfOpen = mock(LogCollectCircuitBreaker.class);
        when(halfOpen.getState()).thenReturn(LogCollectCircuitBreaker.State.HALF_OPEN);
        registry.register("demo#halfOpen", halfOpen);
        ReflectionTestUtils.setField(indicator, "circuitBreakerRegistry", registry);

        Health health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    }

    @Test
    void health_openBreaker_andOptionalComponents_reportsDownWithDetails() {
        LogCollectHealthIndicator indicator = new LogCollectHealthIndicator();

        CircuitBreakerRegistry registry = new CircuitBreakerRegistry();
        LogCollectCircuitBreaker open = mock(LogCollectCircuitBreaker.class);
        when(open.getState()).thenReturn(LogCollectCircuitBreaker.State.OPEN);
        registry.register("demo#open", open);
        ReflectionTestUtils.setField(indicator, "circuitBreakerRegistry", registry);

        LogCollectMetrics metrics = mock(LogCollectMetrics.class);
        when(metrics.getActiveCollections()).thenReturn(2);
        when(metrics.getTotalCollected()).thenReturn(100L);
        when(metrics.getTotalPersisted()).thenReturn(80L);
        when(metrics.getTotalDiscarded()).thenReturn(20L);
        when(metrics.getTotalFlushes()).thenReturn(10L);
        when(metrics.getTotalSanitizeHits()).thenReturn(3L);
        when(metrics.getTotalMaskHits()).thenReturn(4L);
        when(metrics.getLastPersistDurationP99()).thenReturn("12ms");
        when(metrics.getGlobalBufferUtilization()).thenReturn(0.55d);
        when(metrics.getBufferUtilizations()).thenReturn(Collections.singletonMap("demo#open", 0.6d));
        ReflectionTestUtils.setField(indicator, "metrics", metrics);

        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setEffectiveCollectMode(CollectMode.AGGREGATE);
        LogCollectConfigResolver resolver = mock(LogCollectConfigResolver.class);
        when(resolver.getAllCachedConfigs()).thenReturn(Collections.singletonMap("demo#open", config));
        ReflectionTestUtils.setField(indicator, "configResolver", resolver);

        DegradeFileManager fileManager = mock(DegradeFileManager.class);
        when(fileManager.isInitialized()).thenReturn(true);
        when(fileManager.getFileCount()).thenReturn(3L);
        when(fileManager.getTotalSizeBytes()).thenReturn(1024L);
        when(fileManager.getDiskFreeSpace()).thenReturn(1024L * 1024L);
        ReflectionTestUtils.setField(indicator, "degradeFileManager", fileManager);

        ReflectionTestUtils.setField(indicator, "globalSwitch", new LogCollectGlobalSwitch(false));

        LogCollectConfigSource source = new LogCollectConfigSource() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public int getOrder() {
                return 7;
            }
        };
        ReflectionTestUtils.setField(indicator, "configSources", Arrays.asList(source));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsKeys(
                "activeCollections",
                "totalCollected",
                "bufferUtilization",
                "collectModes",
                "degradeFileCount",
                "degradeFileTotalSize",
                "configSources");
        Object breakerStates = health.getDetails().get("circuitBreakers");
        assertThat(breakerStates).asString().contains("OPEN");
        Object collectModes = health.getDetails().get("collectModes");
        assertThat(collectModes).asString().contains("AGGREGATE");
        Object sources = health.getDetails().get("configSources");
        assertThat(sources).asString().contains("available=false");
    }
}

