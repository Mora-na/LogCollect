package com.logcollect.core.context;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

class LogCollectContextUtilsTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
    }

    @Test
    void wrapConsumerShouldPropagateContext() throws Exception {
        LogCollectContext context = newContext("trace-consumer");
        LogCollectContextManager.push(context);

        AtomicReference<String> traceRef = new AtomicReference<String>();
        Consumer<String> wrapped = LogCollectContextUtils.wrapConsumer(new Consumer<String>() {
            @Override
            public void accept(String value) {
                LogCollectContext current = LogCollectContextManager.current();
                traceRef.set(current == null ? null : current.getTraceId());
            }
        });

        LogCollectContextManager.clear();
        wrapped.accept("x");

        Assertions.assertEquals("trace-consumer", traceRef.get());
        Assertions.assertNull(LogCollectContextManager.current());
    }

    @Test
    void wrapExecutorServiceShouldKeepScheduledSemantics() throws Exception {
        LogCollectContext context = newContext("trace-scheduled");
        LogCollectContextManager.push(context);

        ScheduledExecutorService raw = Executors.newSingleThreadScheduledExecutor();
        try {
            ExecutorService wrapped = LogCollectContextUtils.wrapExecutorService(raw);
            Assertions.assertTrue(wrapped instanceof ScheduledExecutorService);

            ScheduledFuture<String> future = ((ScheduledExecutorService) wrapped)
                    .schedule(new Callable<String>() {
                        @Override
                        public String call() {
                            LogCollectContext current = LogCollectContextManager.current();
                            return current == null ? null : current.getTraceId();
                        }
                    }, 0, TimeUnit.MILLISECONDS);

            LogCollectContextManager.clear();
            Assertions.assertEquals("trace-scheduled", future.get(2, TimeUnit.SECONDS));
        } finally {
            raw.shutdownNow();
        }
    }

    private LogCollectContext newContext(String traceId) throws Exception {
        Method method = LogCollectContextUtilsTest.class.getDeclaredMethod("marker");
        return new LogCollectContext(traceId, method, new Object[0],
                LogCollectConfig.frameworkDefaults(), new LogCollectHandler() {
                }, null, null, CollectMode.SINGLE);
    }

    private static void marker() {
    }
}
