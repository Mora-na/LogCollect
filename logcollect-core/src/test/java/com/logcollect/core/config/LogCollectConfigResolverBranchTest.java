package com.logcollect.core.config;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectConfigResolverBranchTest {

    @Test
    void constructor_syncsGlobalEnabled_andOnConfigChangeDefaultMethodWorks() throws Exception {
        LogCollectGlobalSwitch globalSwitch = new LogCollectGlobalSwitch(true);
        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                Collections.singletonList(new Source(
                        mapOf("enabled", "false", "format.log-line-pattern", "%msg"),
                        Collections.emptyMap(),
                        true,
                        false,
                        false)),
                null,
                globalSwitch,
                null);

        assertThat(globalSwitch.isEnabled()).isFalse();
        assertThat(resolver.getLastRefreshTimeFormatted()).isEqualTo("never");

        resolver.onConfigChange();
        assertThat(resolver.getLastRefreshTime()).isNotNull();
        assertThat(resolver.getLastRefreshTimeFormatted()).isNotEqualTo("never");

        Method target = LogCollectConfigResolverBranchTest.class.getDeclaredMethod("targetMethod");
        LogCollectConfig config = resolver.resolve(target, null);
        assertThat(config.getLevel()).isEqualTo("INFO");
    }

    @Test
    void resolve_applyCsvAndGlobalOnlyFilter_andSourceExceptionsCovered() throws Exception {
        LogCollectConfigSource unavailable = new Source(
                mapOf("level", "WARN"),
                mapOf("exclude-loggers", "a, ,b,  ", "degrade.file.ttl-days", "1"),
                false,
                false,
                false);
        LogCollectConfigSource exceptionSource = new Source(
                mapOf("level", "ERROR"),
                mapOf("exclude-loggers", "x,y"),
                true,
                true,
                true);
        LogCollectConfigSource available = new Source(
                mapOf("level", "WARN"),
                mapOf("exclude-loggers", "svc.a, svc.b", "metrics.enabled", "true"),
                true,
                false,
                false);

        LogCollectConfigResolver resolver = new LogCollectConfigResolver(
                java.util.Arrays.asList(unavailable, exceptionSource, available),
                null);

        Method target = LogCollectConfigResolverBranchTest.class.getDeclaredMethod("targetMethod");
        LogCollectConfig config = resolver.resolve(target, null);
        assertThat(config.getLevel()).isEqualTo("WARN");
        assertThat(config.getExcludeLoggerPrefixes()).containsExactly("svc.a", "svc.b");
        assertThat(config.getDegradeFileTTLDays()).isEqualTo(90);
    }

    @SuppressWarnings("unused")
    private static void targetMethod() {
    }

    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static final class Source implements LogCollectConfigSource {
        private final Map<String, String> global;
        private final Map<String, String> method;
        private final boolean available;
        private final boolean throwGlobal;
        private final boolean throwMethod;

        private Source(Map<String, String> global,
                       Map<String, String> method,
                       boolean available,
                       boolean throwGlobal,
                       boolean throwMethod) {
            this.global = global;
            this.method = method;
            this.available = available;
            this.throwGlobal = throwGlobal;
            this.throwMethod = throwMethod;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Map<String, String> getGlobalProperties() {
            if (throwGlobal) {
                throw new RuntimeException("global error");
            }
            return global;
        }

        @Override
        public Map<String, String> getMethodProperties(String methodKey) {
            if (throwMethod) {
                throw new RuntimeException("method error");
            }
            return method;
        }
    }
}
