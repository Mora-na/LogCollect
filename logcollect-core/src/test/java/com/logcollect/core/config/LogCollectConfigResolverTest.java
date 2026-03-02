package com.logcollect.core.config;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.backpressure.BackpressureAction;
import com.logcollect.api.backpressure.BackpressureCallback;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.enums.SamplingStrategy;
import com.logcollect.api.enums.TotalLimitPolicy;
import com.logcollect.api.model.LogCollectConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

class LogCollectConfigResolverTest {

    @Test
    void shouldIgnoreGlobalOnlyKeysAtMethodLevel() throws Exception {
        Map<String, String> global = new LinkedHashMap<String, String>();
        global.put("guard.max-content-length", "4096");

        Map<String, String> method = new LinkedHashMap<String, String>();
        method.put("guard.max-content-length", "128");
        method.put("degrade.file.ttl-days", "7");
        method.put("buffer.total-max-bytes", "1KB");

        LogCollectConfigSource source = new InMemorySource(global, method);
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                Collections.singletonList(source), null);

        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("targetMethod");
        LogCollectConfig config = resolver.resolve(target, (LogCollect) null);

        Assertions.assertEquals(4096, config.getGuardMaxContentLength());
        Assertions.assertEquals(90, config.getDegradeFileTTLDays());
        Assertions.assertEquals(100L * 1024L * 1024L, config.getGlobalBufferTotalMaxBytes());
    }

    @Test
    void shouldApplySamplingAndTotalLimitProperties() throws Exception {
        Map<String, String> global = new LinkedHashMap<String, String>();
        global.put("max-total-collect", "2048");
        global.put("max-total-collect-bytes", "12MB");
        global.put("total-limit-policy", "SAMPLE");
        global.put("sampling-rate", "0.25");
        global.put("sampling-strategy", "ADAPTIVE");
        global.put("degrade.window-size", "16");
        global.put("degrade.failure-rate-threshold", "0.75");

        LogCollectConfigSource source = new InMemorySource(global, Collections.emptyMap());
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                Collections.singletonList(source), null);

        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("targetMethod");
        LogCollectConfig config = resolver.resolve(target, (LogCollect) null);

        Assertions.assertEquals(2048, config.getMaxTotalCollect());
        Assertions.assertEquals(12L * 1024L * 1024L, config.getMaxTotalCollectBytes());
        Assertions.assertEquals(TotalLimitPolicy.SAMPLE, config.getTotalLimitPolicy());
        Assertions.assertEquals(0.25d, config.getSamplingRate(), 0.00001d);
        Assertions.assertEquals(SamplingStrategy.ADAPTIVE, config.getSamplingStrategy());
        Assertions.assertEquals(16, config.getDegradeWindowSize());
        Assertions.assertEquals(0.75d, config.getDegradeFailureRateThreshold(), 0.00001d);
    }

    @Test
    void shouldPreferMethodOverGlobalOverAnnotation() throws Exception {
        Map<String, String> global = new LinkedHashMap<String, String>();
        global.put("level", "WARN");
        Map<String, String> method = new LinkedHashMap<String, String>();
        method.put("level", "ERROR");

        LogCollectConfigSource source = new InMemorySource(global, method);
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                Collections.singletonList(source), null);

        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("annotatedWithDebug");
        LogCollect annotation = target.getAnnotation(LogCollect.class);
        LogCollectConfig config = resolver.resolve(target, annotation);

        Assertions.assertEquals("ERROR", config.getLevel());
    }

    @Test
    void shouldUseFrameworkDefaultLevelWhenAnnotationMinLevelIsEmpty() throws Exception {
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.emptyList(), null);
        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("annotatedWithDefaultMinLevel");
        LogCollect annotation = target.getAnnotation(LogCollect.class);

        LogCollectConfig config = resolver.resolve(target, annotation);

        Assertions.assertEquals("INFO", config.getLevel());
    }

    @Test
    void shouldResolveBackpressureFromAnnotation() throws Exception {
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.emptyList(), null);
        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("annotatedWithBackpressure");
        LogCollect annotation = target.getAnnotation(LogCollect.class);

        LogCollectConfig config = resolver.resolve(target, annotation);

        Assertions.assertEquals(TestBackpressureCallback.class, config.getBackpressureCallbackClass());
    }

    @Test
    void resolve_noConfigCenter_fallbackToAnnotation() throws Exception {
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.emptyList(), null);
        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("annotatedWithDebug");
        LogCollect annotation = target.getAnnotation(LogCollect.class);
        LogCollectConfig config = resolver.resolve(target, annotation);
        Assertions.assertEquals("DEBUG", config.getLevel());
    }

    @Test
    void resolve_noAnnotation_fallbackToDefault() throws Exception {
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.emptyList(), null);
        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("targetMethod");
        LogCollectConfig config = resolver.resolve(target, null);
        Assertions.assertEquals("INFO", config.getLevel());
    }

    @Test
    void resolve_configSourceUnavailable_usesLocalCache() throws Exception {
        Path tempDir = Files.createTempDirectory("logcollect-config-cache");
        Path cacheFile = tempDir.resolve("local.properties");
        LogCollectLocalConfigCache cache = new LogCollectLocalConfigCache(cacheFile, 7);
        Map<String, String> cached = new LinkedHashMap<String, String>();
        cached.put("logcollect.global.level", "ERROR");
        cached.put("logcollect.methods." + targetMethodKey() + ".async", "false");
        cache.save(cached);

        LogCollectConfigSource unavailable = new InMemorySource(Collections.emptyMap(), Collections.emptyMap()) {
            @Override
            public boolean isAvailable() {
                return false;
            }
        };

        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                Collections.singletonList(unavailable), cache);
        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("targetMethod");
        LogCollectConfig config = resolver.resolve(target, null);
        Assertions.assertEquals("ERROR", config.getLevel());
        Assertions.assertFalse(config.isAsync());
    }

    @Test
    void resolve_cachedConfigExpired_fallsBackToDefault() throws Exception {
        Path tempDir = Files.createTempDirectory("logcollect-config-cache-expired");
        Path cacheFile = tempDir.resolve("local.properties");
        LogCollectLocalConfigCache cache = new LogCollectLocalConfigCache(cacheFile, 0);
        Map<String, String> cached = new LinkedHashMap<String, String>();
        cached.put("logcollect.global.level", "ERROR");
        cache.save(cached);

        LogCollectConfigResolver resolver = new LogCollectConfigResolver(Collections.emptyList(), cache);
        Method target = LogCollectConfigResolverTest.class.getDeclaredMethod("targetMethod");
        LogCollectConfig config = resolver.resolve(target, null);
        Assertions.assertEquals("INFO", config.getLevel());
    }

    private static class InMemorySource implements LogCollectConfigSource {
        private final Map<String, String> global;
        private final Map<String, String> method;

        private InMemorySource(Map<String, String> global, Map<String, String> method) {
            this.global = global;
            this.method = method;
        }

        @Override
        public Map<String, String> getGlobalProperties() {
            return global;
        }

        @Override
        public Map<String, String> getMethodProperties(String methodKey) {
            return method;
        }
    }

    private static void targetMethod() {
    }

    @LogCollect(minLevel = "DEBUG")
    private static void annotatedWithDebug() {
    }

    @LogCollect
    private static void annotatedWithDefaultMinLevel() {
    }

    @LogCollect(backpressure = TestBackpressureCallback.class)
    private static void annotatedWithBackpressure() {
    }

    public static class TestBackpressureCallback implements BackpressureCallback {
        @Override
        public BackpressureAction onPressure(double utilization) {
            return BackpressureAction.CONTINUE;
        }
    }

    private String targetMethodKey() throws NoSuchMethodException {
        Method method = LogCollectConfigResolverTest.class.getDeclaredMethod("targetMethod");
        return method.getDeclaringClass().getName().replace('.', '_') + "_" + method.getName();
    }
}
