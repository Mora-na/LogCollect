package com.logcollect.core.config;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.enums.SamplingStrategy;
import com.logcollect.api.enums.TotalLimitPolicy;
import com.logcollect.api.model.LogCollectConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
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
}
