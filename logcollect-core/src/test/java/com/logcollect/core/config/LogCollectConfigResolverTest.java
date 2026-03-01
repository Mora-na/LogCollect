package com.logcollect.core.config;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.config.LogCollectConfigSource;
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
