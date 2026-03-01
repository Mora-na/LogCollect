package com.logcollect.autoconfigure.async;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.context.LogCollectContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogCollectThreadPoolBPPTest {

    private final LogCollectThreadPoolBPP bpp = new LogCollectThreadPoolBPP();

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
    }

    @Test
    void shouldPropagateContextWhenAppliedBeforeInitialization() throws Exception {
        ThreadPoolTaskExecutor executor = newExecutor("bpp-before-init-");
        bpp.postProcessBeforeInitialization(executor, "jobAsyncExecutor");
        executor.initialize();
        try {
            LogCollectContextManager.push(new LogCollectContext("trace-before", null, null, null, null, null, null, null));
            Future<Boolean> future = executor.submit(LogCollectContextUtils::isInLogCollectContext);
            assertTrue(future.get(5, TimeUnit.SECONDS));
        } finally {
            LogCollectContextManager.clear();
            executor.shutdown();
        }
    }

    @Test
    void shouldPreserveExistingTaskDecorator() throws Exception {
        AtomicBoolean existingDecoratorInvoked = new AtomicBoolean(false);
        ThreadPoolTaskExecutor executor = newExecutor("bpp-chain-");
        executor.setTaskDecorator(new TaskDecorator() {
            @Override
            public Runnable decorate(Runnable runnable) {
                return () -> {
                    existingDecoratorInvoked.set(true);
                    runnable.run();
                };
            }
        });
        bpp.postProcessBeforeInitialization(executor, "jobAsyncExecutor");
        executor.initialize();
        try {
            LogCollectContextManager.push(new LogCollectContext("trace-chain", null, null, null, null, null, null, null));
            Future<Boolean> future = executor.submit(LogCollectContextUtils::isInLogCollectContext);
            assertTrue(future.get(5, TimeUnit.SECONDS));
            assertTrue(existingDecoratorInvoked.get());
        } finally {
            LogCollectContextManager.clear();
            executor.shutdown();
        }
    }

    @Test
    void shouldNotAffectExecutorInitializedBeforeWrapping() throws Exception {
        ThreadPoolTaskExecutor executor = newExecutor("bpp-too-late-");
        executor.initialize();
        try {
            bpp.postProcessBeforeInitialization(executor, "jobAsyncExecutor");
            LogCollectContextManager.push(new LogCollectContext("trace-late", null, null, null, null, null, null, null));
            Future<Boolean> future = executor.submit(LogCollectContextUtils::isInLogCollectContext);
            assertFalse(future.get(5, TimeUnit.SECONDS));
        } finally {
            LogCollectContextManager.clear();
            executor.shutdown();
        }
    }

    private ThreadPoolTaskExecutor newExecutor(String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix(prefix);
        return executor;
    }
}
