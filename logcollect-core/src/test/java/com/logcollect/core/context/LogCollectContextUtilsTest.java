package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LogCollectContextUtilsTest extends CoreUnitTestBase {

    @Test
    void wrapRunnable_propagatesContext() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        AtomicReference<String> childTraceId = new AtomicReference<String>();
        Runnable wrapped = LogCollectContextUtils.wrapRunnable(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            childTraceId.set(current == null ? null : current.getTraceId());
        });

        Thread t = new Thread(wrapped);
        t.start();
        t.join();

        assertThat(childTraceId.get()).isEqualTo(ctx.getTraceId());
        LogCollectContextManager.pop();
    }

    @Test
    void wrapRunnable_cleansUpAfterExecution() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        AtomicBoolean hasContextAfter = new AtomicBoolean(true);
        Runnable wrapped = LogCollectContextUtils.wrapRunnable(() -> {
            // no-op
        });

        Thread t = new Thread(() -> {
            wrapped.run();
            hasContextAfter.set(LogCollectContextManager.current() != null);
        });
        t.start();
        t.join();

        assertThat(hasContextAfter.get()).isFalse();
        LogCollectContextManager.pop();
    }

    @Test
    void wrapRunnable_exceptionInTask_stillCleansUp() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        AtomicBoolean contextLeaked = new AtomicBoolean(true);
        Runnable wrapped = LogCollectContextUtils.wrapRunnable(() -> {
            throw new RuntimeException("test error");
        });

        Thread t = new Thread(() -> {
            try {
                wrapped.run();
            } catch (RuntimeException ignored) {
                // ignore
            }
            contextLeaked.set(LogCollectContextManager.current() != null);
        });
        t.start();
        t.join();

        assertThat(contextLeaked.get()).isFalse();
        LogCollectContextManager.pop();
    }

    @Test
    void wrapRunnable_noContext_executesNormally() {
        AtomicBoolean executed = new AtomicBoolean(false);
        Runnable wrapped = LogCollectContextUtils.wrapRunnable(() -> executed.set(true));
        wrapped.run();
        assertThat(executed.get()).isTrue();
    }

    @Test
    void wrapCallable_propagatesAndReturnsValue() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        Callable<String> wrapped = LogCollectContextUtils.wrapCallable(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            return current == null ? null : current.getTraceId();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        String result = pool.submit(wrapped).get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(result).isEqualTo(ctx.getTraceId());
        LogCollectContextManager.pop();
    }

    @Test
    void wrapExecutorService_allTasksGetContext() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        ExecutorService raw = Executors.newFixedThreadPool(4);
        ExecutorService wrapped = LogCollectContextUtils.wrapExecutorService(raw);

        List<Future<String>> futures = new ArrayList<Future<String>>();
        for (int i = 0; i < 10; i++) {
            futures.add(wrapped.submit(() -> {
                LogCollectContext c = LogCollectContextManager.current();
                return c == null ? "NONE" : c.getTraceId();
            }));
        }

        for (Future<String> future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(ctx.getTraceId());
        }

        wrapped.shutdownNow();
        LogCollectContextManager.pop();
    }

    @Test
    void staticAccessors_noContext_safeDefaults() {
        assertThat(LogCollectContext.current()).isNull();
        assertThat(LogCollectContext.isActive()).isFalse();
        assertThat(LogCollectContext.getCurrentTraceId()).isNull();
        assertThat(LogCollectContext.getCurrentCollectedCount()).isEqualTo(0);
        assertThat(LogCollectContext.getCurrentBusinessId(String.class)).isNull();
        assertThat(LogCollectContext.getCurrentAttribute("key")).isNull();
        assertThat(LogCollectContext.currentHasAttribute("key")).isFalse();
        assertDoesNotThrow(() -> LogCollectContext.setCurrentBusinessId("id"));
        assertDoesNotThrow(() -> LogCollectContext.setCurrentAttribute("k", "v"));
    }

    @Test
    void diagnosticInfo_withContext_containsDepth() {
        LogCollectContextManager.push(createTestContext());
        String info = LogCollectContextUtils.diagnosticInfo();
        assertThat(info).contains("depth=1");
        LogCollectContextManager.pop();
    }

    @Test
    void diagnosticInfo_noContext_safeOutput() {
        String info = LogCollectContextUtils.diagnosticInfo();
        assertThat(info).isEqualTo("depth=0");
    }
}
