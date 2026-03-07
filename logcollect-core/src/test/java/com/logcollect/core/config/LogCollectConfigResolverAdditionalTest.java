package com.logcollect.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.util.MethodKeyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectConfigResolverAdditionalTest {
    private static final long MB = 1024L * 1024L;

    @TempDir
    Path tempDir;

    @Test
    void resolve_priorityOrder_methodThenGlobalThenAnnotationThenDefault() throws Exception {
        Method method = LogCollectConfigResolverAdditionalTest.class.getDeclaredMethod("annotatedMethod");
        LogCollect annotation = method.getAnnotation(LogCollect.class);

        LogCollectConfigSource source = new InMemorySource(
                mapOf("level", "WARN", "buffer.max-size", "200"),
                mapOf("level", "ERROR"),
                100);

        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.singletonList(source), null);
        LogCollectConfig config = resolver.resolve(method, annotation);

        assertThat(config.getLevel()).isEqualTo("ERROR");
        assertThat(config.getMaxBufferSize()).isEqualTo(200);
    }

    @Test
    void resolve_higherPrioritySourceOverridesLowerPriority() throws Exception {
        Method method = LogCollectConfigResolverAdditionalTest.class.getDeclaredMethod("targetMethod");

        LogCollectConfigSource lowPriority = new InMemorySource(mapOf("level", "INFO"), Collections.emptyMap(), 200);
        LogCollectConfigSource highPriority = new InMemorySource(mapOf("level", "ERROR"), Collections.emptyMap(), 10);

        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Arrays.asList(lowPriority, highPriority), null);
        LogCollectConfig config = resolver.resolve(method, null);
        assertThat(config.getLevel()).isEqualTo("ERROR");
    }

    @Test
    void onConfigChange_clearsCache_and_recordsRefreshTime() throws Exception {
        Method method = LogCollectConfigResolverAdditionalTest.class.getDeclaredMethod("targetMethod");
        MetricsStub metrics = new MetricsStub();
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                Collections.singletonList(new InMemorySource(mapOf("enabled", "true"), Collections.emptyMap(), 100)),
                null,
                null,
                metrics);

        resolver.resolve(method, null);
        assertThat(resolver.getCacheSize()).isEqualTo(1);

        resolver.onConfigChange("unit-test");
        assertThat(resolver.getCacheSize()).isZero();
        assertThat(resolver.getLastRefreshTime()).isNotNull();
        assertThat(resolver.getLastRefreshTime()).isBeforeOrEqualTo(Instant.now());
        assertThat(metrics.refreshSource).isEqualTo("unit-test");
    }

    @Test
    void getCachedConfig_latestGlobalProperties_and_allCacheViews_work() throws Exception {
        Method method = LogCollectConfigResolverAdditionalTest.class.getDeclaredMethod("targetMethod");
        InMemorySource source = new InMemorySource(mapOf("level", "WARN"), mapOf("async", "false"), 100);
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.singletonList(source), null);

        LogCollectConfig resolved = resolver.resolve(method, null);
        String displayKey = MethodKeyResolver.toDisplayKey(method);
        String configKey = MethodKeyResolver.toConfigKey(method);

        assertThat(resolver.getCachedConfig(displayKey)).isSameAs(resolved);
        assertThat(resolver.getCachedConfig(configKey)).isSameAs(resolved);
        assertThat(resolver.getAllCachedConfigs()).hasSize(1);
        assertThat(resolver.getLatestGlobalProperties()).containsEntry("level", "WARN");
        assertThat(resolver.getCachedConfig(null)).isNull();
    }

    @Test
    void saveToLocalCache_persistsSourceValues() throws Exception {
        Path cacheFile = tempDir.resolve("resolver-cache.properties");
        LogCollectLocalConfigCache cache = new LogCollectLocalConfigCache(cacheFile, 7);
        InMemorySource source = new InMemorySource(
                mapOf("level", "WARN"),
                mapOf("async", "false"),
                100);
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.singletonList(source), cache);

        resolver.saveToLocalCache();

        Map<String, String> loaded = cache.load();
        assertThat(loaded).containsEntry("logcollect.global.level", "WARN");
    }

    @Test
    void merge_invalidNumericValues_keepDefaults() throws Exception {
        Method method = LogCollectConfigResolverAdditionalTest.class.getDeclaredMethod("targetMethod");
        InMemorySource source = new InMemorySource(
                mapOf("max-total-collect", "invalid", "sampling-rate", "bad"),
                Collections.emptyMap(),
                100);
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.singletonList(source), null);
        LogCollectConfig config = resolver.resolve(method, null);

        assertThat(config.getMaxTotalCollect()).isEqualTo(100000);
        assertThat(config.getSamplingRate()).isEqualTo(1.0d);
    }

    @Test
    void getCachedConfig_unknownKey_and_saveWithNullCache_areNoop() {
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.<LogCollectConfigSource>emptyList(), null);

        assertThat(resolver.getCachedConfig("com.example.Unknown#method()")).isNull();
        resolver.saveToLocalCache();
    }

    @Test
    void constructor_derivesRuntimeHardCeilingWhenOnlySoftLimitIsConfigured() {
        MetricsStub metrics = new MetricsStub();
        GlobalBufferMemoryManager manager = new GlobalBufferMemoryManager(100L * MB);

        new LogCollectConfigResolver(
                Collections.singletonList(new InMemorySource(
                        mapOf("buffer.total-max-bytes", "200MB"),
                        Collections.emptyMap(),
                        100)),
                null,
                null,
                metrics,
                manager);

        assertThat(manager.getMaxTotalBytes()).isEqualTo(200L * MB);
        assertThat(manager.getHardCeilingBytes()).isEqualTo(300L * MB);
        assertThat(metrics.refreshSource).isEqualTo("buffer_limits");
    }

    @Test
    void constructor_logsDerivedRuntimeHardCeilingWhenOnlySoftLimitIsConfigured() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.logcollect.internal");
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        LogCollectInternalLogger.setLevel(LogCollectInternalLogger.Level.INFO);
        try {
            new LogCollectConfigResolver(
                    Collections.singletonList(new InMemorySource(
                            mapOf("buffer.total-max-bytes", "200MB"),
                            Collections.emptyMap(),
                            100)),
                    null,
                    null,
                    null,
                    new GlobalBufferMemoryManager(100L * MB));

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("buffer.hard-ceiling-bytes not configured")
                            .contains("total-max-bytes=200MB")
                            .contains("314572800 bytes")
                            .contains("soft * 1.5")
                            .contains("source=startup"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @LogCollect(minLevel = "DEBUG", maxBufferSize = 123)
    private static void annotatedMethod() {
    }

    private static void targetMethod() {
    }

    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static class InMemorySource implements LogCollectConfigSource {
        private final Map<String, String> global;
        private final Map<String, String> method;
        private final int order;

        private InMemorySource(Map<String, String> global, Map<String, String> method, int order) {
            this.global = global;
            this.method = method;
            this.order = order;
        }

        @Override
        public Map<String, String> getGlobalProperties() {
            return global;
        }

        @Override
        public Map<String, String> getMethodProperties(String methodKey) {
            return method;
        }

        @Override
        public int getOrder() {
            return order;
        }
    }

    @SuppressWarnings("unused")
    public static class MetricsStub implements com.logcollect.api.metrics.LogCollectMetrics {
        private String refreshSource;

        @Override
        public void incrementConfigRefresh(String source) {
            this.refreshSource = source;
        }

        @Override
        public void incrementDiscarded(String methodKey, String reason) {
        }

        @Override
        public void incrementCollected(String methodKey, String level, String mode) {
        }

        @Override
        public void incrementPersisted(String methodKey, String mode) {
        }

        @Override
        public void incrementPersistFailed(String methodKey) {
        }

        @Override
        public void incrementFlush(String methodKey, String mode, String trigger) {
        }

        @Override
        public void incrementBufferOverflow(String methodKey, String overflowPolicy) {
        }

        @Override
        public void incrementDegradeTriggered(String type, String methodKey) {
        }

        @Override
        public void incrementCircuitRecovered(String methodKey) {
        }

        @Override
        public void incrementSanitizeHits(String methodKey) {
        }

        @Override
        public void incrementMaskHits(String methodKey) {
        }

        @Override
        public void incrementHandlerTimeout(String methodKey) {
        }

        @Override
        public void updateBufferUtilization(String methodKey, double utilization) {
        }

        @Override
        public void updateGlobalBufferUtilization(double utilization) {
        }

        @Override
        public Object startSecurityTimer() {
            return null;
        }

        @Override
        public void stopSecurityTimer(Object timerSample, String methodKey) {
        }

        @Override
        public Object startPersistTimer() {
            return null;
        }

        @Override
        public void stopPersistTimer(Object timerSample, String methodKey, String mode) {
        }
    }
}
