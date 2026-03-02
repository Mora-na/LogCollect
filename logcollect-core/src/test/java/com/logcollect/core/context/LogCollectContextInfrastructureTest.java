package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectContextInfrastructureTest extends CoreUnitTestBase {

    @AfterEach
    void clearThreadLocalAfterEach() {
        LogCollectContextManager.clear();
    }

    @Test
    void ignoreManager_nestedEnterExit_isConsistent() {
        assertThat(LogCollectIgnoreManager.isIgnored()).isFalse();

        LogCollectIgnoreManager.enter();
        LogCollectIgnoreManager.enter();
        assertThat(LogCollectIgnoreManager.isIgnored()).isTrue();

        LogCollectIgnoreManager.exit();
        assertThat(LogCollectIgnoreManager.isIgnored()).isTrue();

        LogCollectIgnoreManager.exit();
        assertThat(LogCollectIgnoreManager.isIgnored()).isFalse();

        LogCollectIgnoreManager.exit();
        assertThat(LogCollectIgnoreManager.isIgnored()).isFalse();

        LogCollectIgnoreManager.enter();
        LogCollectIgnoreManager.clear();
        assertThat(LogCollectIgnoreManager.isIgnored()).isFalse();
    }

    @Test
    void runnableWrapper_restoresContext_andCleans() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);
        LogCollectContextSnapshot snapshot = LogCollectContextManager.captureSnapshot();
        LogCollectContextManager.clear();

        AtomicReference<String> trace = new AtomicReference<String>();
        AtomicReference<Boolean> leaked = new AtomicReference<Boolean>(Boolean.TRUE);
        Runnable wrapped = new LogCollectRunnableWrapper(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            trace.set(current == null ? null : current.getTraceId());
        }, snapshot);

        Thread thread = new Thread(() -> {
            wrapped.run();
            leaked.set(LogCollectContextManager.current() != null);
        });
        thread.start();
        thread.join(3000);

        assertThat(trace.get()).isEqualTo(ctx.getTraceId());
        assertThat(leaked.get()).isFalse();
    }

    @Test
    void callableWrapper_restoresContext_andCleans() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);
        LogCollectContextSnapshot snapshot = LogCollectContextManager.captureSnapshot();
        LogCollectContextManager.clear();

        Callable<String> wrapped = new LogCollectCallableWrapper<String>(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            return current == null ? null : current.getTraceId();
        }, snapshot);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            String trace = pool.submit(wrapped).get(3, TimeUnit.SECONDS);
            assertThat(trace).isEqualTo(ctx.getTraceId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void threadLocalAccessor_getSetReset_setValueNoArg() {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        LogCollectThreadLocalAccessor accessor = new LogCollectThreadLocalAccessor();
        assertThat(accessor.key()).isEqualTo(LogCollectThreadLocalAccessor.KEY);

        LogCollectContextSnapshot snapshot = accessor.getValue();
        assertThat(snapshot.getTraceId()).isEqualTo(ctx.getTraceId());

        LogCollectContextManager.clear();
        accessor.setValue(snapshot);
        assertThat(LogCollectContextManager.current()).isNotNull();
        assertThat(LogCollectContextManager.current().getTraceId()).isEqualTo(ctx.getTraceId());

        accessor.reset();
        assertThat(LogCollectContextManager.current()).isNull();

        LogCollectContextManager.push(ctx);
        accessor.setValue();
        assertThat(LogCollectContextManager.current()).isNull();
    }

    @Test
    void executorsWrap_returnsExpectedWrapperTypes() {
        ExecutorService normal = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor();
        try {
            ExecutorService normalWrapped = LogCollectExecutors.wrap(normal);
            ExecutorService scheduledWrapped = LogCollectExecutors.wrap(scheduled);
            assertThat(normalWrapped).isInstanceOf(LogCollectExecutorServiceWrapper.class);
            assertThat(scheduledWrapped).isInstanceOf(LogCollectScheduledExecutorServiceWrapper.class);
        } finally {
            normal.shutdownNow();
            scheduled.shutdownNow();
        }
    }

    @Test
    void executorServiceWrapper_invokeAll_invokeAny_propagatesContext() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        ExecutorService raw = Executors.newFixedThreadPool(2);
        LogCollectExecutorServiceWrapper wrapped = new LogCollectExecutorServiceWrapper(raw);
        try {
            List<Callable<String>> tasks = new ArrayList<Callable<String>>();
            tasks.add(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                return current == null ? null : current.getTraceId();
            });
            tasks.add(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                return current == null ? null : current.getTraceId();
            });

            List<Future<String>> allResults = wrapped.invokeAll(tasks);
            for (Future<String> result : allResults) {
                assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo(ctx.getTraceId());
            }

            String anyResult = wrapped.invokeAny(tasks, 3, TimeUnit.SECONDS);
            assertThat(anyResult).isEqualTo(ctx.getTraceId());
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void scheduledExecutorWrapper_scheduleWithFixedDelay_propagatesContext() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        ScheduledExecutorService raw = Executors.newSingleThreadScheduledExecutor();
        LogCollectScheduledExecutorServiceWrapper wrapped = new LogCollectScheduledExecutorServiceWrapper(raw);
        try {
            Future<String> once = wrapped.schedule(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                return current == null ? null : current.getTraceId();
            }, 10, TimeUnit.MILLISECONDS);
            assertThat(once.get(3, TimeUnit.SECONDS)).isEqualTo(ctx.getTraceId());

            CountDownLatch latch = new CountDownLatch(2);
            AtomicReference<String> trace = new AtomicReference<String>();
            ScheduledFuture<?> periodic = wrapped.scheduleWithFixedDelay(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                trace.set(current == null ? null : current.getTraceId());
                latch.countDown();
            }, 0, 10, TimeUnit.MILLISECONDS);
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            periodic.cancel(true);
            assertThat(trace.get()).isEqualTo(ctx.getTraceId());
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void executorWrapper_execute_propagatesContext() throws Exception {
        LogCollectContext ctx = createTestContext();
        LogCollectContextManager.push(ctx);

        ExecutorService raw = Executors.newSingleThreadExecutor();
        LogCollectExecutorWrapper wrapper = new LogCollectExecutorWrapper(raw);
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> trace = new AtomicReference<String>();
            wrapper.execute(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                trace.set(current == null ? null : current.getTraceId());
                latch.countDown();
            });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(trace.get()).isEqualTo(ctx.getTraceId());
        } finally {
            raw.shutdownNow();
        }
    }
}
