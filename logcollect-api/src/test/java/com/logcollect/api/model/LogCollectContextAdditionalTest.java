package com.logcollect.api.model;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LogCollectContextAdditionalTest {

    @Test
    void constructorAndBasicAccessors_work() throws Exception {
        Method method = LogCollectContextAdditionalTest.class.getDeclaredMethod("marker", String.class);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setLevel("WARN");
        config.setExcludeLoggerPrefixes(new String[]{"com.internal.", "org.skip."});
        LogCollectHandler handler = new LogCollectHandler() {
        };

        LogCollectContext context = new LogCollectContext(
                "trace-1", method, new Object[]{"arg1"}, config, handler, "buffer", "breaker", CollectMode.SINGLE);

        assertThat(context.getTraceId()).isEqualTo("trace-1");
        assertThat(context.getMethod()).isEqualTo(method);
        assertThat(context.getMethodSignature()).contains("#marker");
        assertThat(context.getClassName()).isEqualTo("LogCollectContextAdditionalTest");
        assertThat(context.getMethodName()).isEqualTo("marker");
        assertThat(context.getCollectMode()).isEqualTo(CollectMode.SINGLE);
        assertThat(context.getHandlerClass()).isEqualTo(handler.getClass());
        assertThat(context.getConfig()).isSameAs(config);
        assertThat(context.getHandler()).isSameAs(handler);
        assertThat(context.getBuffer()).isEqualTo("buffer");
        assertThat(context.getCircuitBreaker()).isEqualTo("breaker");
        assertThat(context.getMinLevelInt()).isEqualTo(3);
        assertThat(context.getStartTime()).isNotNull();
        assertThat(context.getStartTimeMillis()).isGreaterThan(0L);
    }

    @Test
    void loggerExcludeAndMutableState_work() throws Exception {
        LogCollectContext context = newContext();
        assertThat(context.isLoggerExcluded("com.internal.A")).isTrue();
        assertThat(context.isLoggerExcluded("com.biz.A")).isFalse();
        assertThat(context.isLoggerExcluded(null)).isFalse();

        context.setReturnValue("ok");
        assertThat(context.getReturnValue()).isEqualTo("ok");

        assertThat(context.hasError()).isFalse();
        context.setError(new RuntimeException("boom"));
        assertThat(context.hasError()).isTrue();
        assertThat(context.getError().getMessage()).isEqualTo("boom");
        assertThat(context.getElapsedMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void countersBusinessIdAttributes_work() throws Exception {
        LogCollectContext context = newContext();

        context.incrementCollectedCount();
        context.incrementCollectedCount(2);
        context.incrementDiscardedCount();
        context.incrementDiscardedCount(3);
        context.addCollectedBytes(64);
        context.incrementFlushCount();

        assertThat(context.getTotalCollectedCount()).isEqualTo(3);
        assertThat(context.getTotalDiscardedCount()).isEqualTo(4);
        assertThat(context.getTotalCollectedBytes()).isEqualTo(64L);
        assertThat(context.getFlushCount()).isEqualTo(1);

        context.setBusinessId(123L);
        assertThat(context.getBusinessId()).isEqualTo(123L);
        assertThat(context.getBusinessId(Long.class)).isEqualTo(123L);

        context.setAttribute("k", "v");
        context.setAttribute("n", 7);
        assertThat(context.getAttribute("k")).isEqualTo("v");
        assertThat(context.getAttribute("n", Integer.class)).isEqualTo(7);
        assertThat(context.hasAttribute("k")).isTrue();
        assertThat(context.hasAttribute("missing")).isFalse();
    }

    @Test
    void methodArgs_returnsDefensiveCopy() throws Exception {
        LogCollectContext context = newContext();
        Object[] args1 = context.getMethodArgs();
        Object[] args2 = context.getMethodArgs();
        assertThat(args1).isNotSameAs(args2);
    }

    @Test
    void staticAccessors_withoutCoreManager_safeDefaults() {
        assertThat(LogCollectContext.current()).isNull();
        assertThat(LogCollectContext.isActive()).isFalse();
        assertThat(LogCollectContext.getCurrentTraceId()).isNull();
        assertThat(LogCollectContext.getCurrentCollectedCount()).isZero();
        assertThat(LogCollectContext.getCurrentBusinessId(String.class)).isNull();
        assertThat(LogCollectContext.getCurrentAttribute("x")).isNull();
        assertThat(LogCollectContext.getCurrentAttribute("x", String.class)).isNull();
        assertThat(LogCollectContext.currentHasAttribute("x")).isFalse();

        assertDoesNotThrow(() -> LogCollectContext.setCurrentBusinessId("id"));
        assertDoesNotThrow(() -> LogCollectContext.setCurrentAttribute("k", "v"));
        assertDoesNotThrow(() -> LogCollectContext.push(null));
        assertDoesNotThrow(LogCollectContext::pop);
    }

    private LogCollectContext newContext() throws Exception {
        Method method = LogCollectContextAdditionalTest.class.getDeclaredMethod("marker", String.class);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setExcludeLoggerPrefixes(new String[]{"com.internal."});
        return new LogCollectContext(
                "trace",
                method,
                new Object[]{"x"},
                config,
                new LogCollectHandler() {
                },
                null,
                null,
                CollectMode.AGGREGATE
        );
    }

    @SuppressWarnings("unused")
    private static LocalDateTime marker(String arg) {
        return LocalDateTime.now();
    }
}
