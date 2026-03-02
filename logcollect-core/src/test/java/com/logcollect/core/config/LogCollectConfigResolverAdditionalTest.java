package com.logcollect.core.config;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.util.MethodKeyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectConfigResolverAdditionalTest {

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
    public static class MetricsStub {
        private String refreshSource;

        public void incrementConfigRefresh(String source) {
            this.refreshSource = source;
        }
    }
}
