package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LogCollectContextUtilsAdditionalTest extends CoreUnitTestBase {

    private LogCollectContext context;

    @BeforeEach
    void setUpContext() {
        context = createTestContext();
        LogCollectContextManager.push(context);
    }

    @AfterEach
    void tearDownContext() {
        LogCollectContextManager.clear();
    }

    @Test
    void wrapRunnable_null_returnsNull() {
        assertThat(LogCollectContextUtils.wrapRunnable(null)).isNull();
    }

    @Test
    void wrapRunnable_alreadyWrapped_returnsSame() {
        Runnable wrapped = new LogCollectRunnableWrapper(() -> { }, LogCollectContextManager.captureSnapshot());
        assertThat(LogCollectContextUtils.wrapRunnable(wrapped)).isSameAs(wrapped);
    }

    @Test
    void wrapCallable_noContext_executesNormally() throws Exception {
        LogCollectContextManager.clear();
        Callable<Integer> callable = LogCollectContextUtils.wrapCallable(() -> 42);
        assertThat(callable.call()).isEqualTo(42);
    }

    @Test
    void wrapCallable_exceptionInTask_cleanupStillRuns() throws Exception {
        Callable<String> wrapped = LogCollectContextUtils.wrapCallable(() -> {
            throw new IllegalStateException("boom");
        });

        AtomicReference<Boolean> leaked = new AtomicReference<Boolean>(Boolean.TRUE);
        Thread thread = new Thread(() -> {
            try {
                wrapped.call();
            } catch (Exception ignored) {
            }
            leaked.set(LogCollectContextManager.current() != null);
        });
        thread.start();
        thread.join(3000);
        assertThat(leaked.get()).isFalse();
    }

    @Test
    void wrapConsumer_withContext_propagatesToChildThread() throws Exception {
        Consumer<String> wrapped = LogCollectContextUtils.wrapConsumer(s -> {
            LogCollectContext current = LogCollectContextManager.current();
            assertThat(current).isNotNull();
            assertThat(current.getTraceId()).isEqualTo(context.getTraceId());
        });
        Thread thread = new Thread(() -> wrapped.accept("payload"));
        thread.start();
        thread.join(3000);
    }

    @Test
    void wrapExecutor_execute_propagates() throws Exception {
        ExecutorService raw = Executors.newSingleThreadExecutor();
        try {
            Executor wrapped = LogCollectContextUtils.wrapExecutor(raw);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> traceId = new AtomicReference<String>();
            wrapped.execute(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                traceId.set(current == null ? null : current.getTraceId());
                latch.countDown();
            });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(traceId.get()).isEqualTo(context.getTraceId());
        } finally {
            raw.shutdownNow();
        }
    }

    @Test
    void wrapExecutorService_executeAndSubmit_propagates() throws Exception {
        ExecutorService raw = Executors.newSingleThreadExecutor();
        ExecutorService wrapped = LogCollectContextUtils.wrapExecutorService(raw);
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> traceInExecute = new AtomicReference<String>();
            wrapped.execute(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                traceInExecute.set(current == null ? null : current.getTraceId());
                latch.countDown();
            });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(traceInExecute.get()).isEqualTo(context.getTraceId());

            String traceInSubmit = wrapped.submit(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                return current == null ? null : current.getTraceId();
            }).get(3, TimeUnit.SECONDS);
            assertThat(traceInSubmit).isEqualTo(context.getTraceId());
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void wrapScheduledExecutorService_allScheduleMethodsPropagate() throws Exception {
        ScheduledExecutorService raw = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService wrapped = LogCollectContextUtils.wrapScheduledExecutorService(raw);
        try {
            Future<String> once = wrapped.schedule(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                return current == null ? null : current.getTraceId();
            }, 10, TimeUnit.MILLISECONDS);
            assertThat(once.get(3, TimeUnit.SECONDS)).isEqualTo(context.getTraceId());

            CountDownLatch latch = new CountDownLatch(2);
            AtomicReference<String> periodicTrace = new AtomicReference<String>();
            ScheduledFuture<?> periodic = wrapped.scheduleAtFixedRate(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                periodicTrace.set(current == null ? null : current.getTraceId());
                latch.countDown();
            }, 0, 10, TimeUnit.MILLISECONDS);
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            periodic.cancel(true);
            assertThat(periodicTrace.get()).isEqualTo(context.getTraceId());
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void newThread_newDaemonThread_threadFactory_wrapThreadFactory_allPropagate() throws Exception {
        AtomicReference<String> t1 = new AtomicReference<String>();
        Thread thread = LogCollectContextUtils.newThread(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            t1.set(current == null ? null : current.getTraceId());
        }, "ctx-thread");
        thread.start();
        thread.join(3000);
        assertThat(thread.getName()).isEqualTo("ctx-thread");
        assertThat(t1.get()).isEqualTo(context.getTraceId());

        AtomicReference<String> t2 = new AtomicReference<String>();
        Thread daemon = LogCollectContextUtils.newDaemonThread(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            t2.set(current == null ? null : current.getTraceId());
        }, "ctx-daemon");
        assertThat(daemon.isDaemon()).isTrue();
        daemon.start();
        daemon.join(3000);
        assertThat(t2.get()).isEqualTo(context.getTraceId());

        AtomicReference<String> t3 = new AtomicReference<String>();
        ThreadFactory factory = LogCollectContextUtils.threadFactory("ctx-pool");
        Thread fromFactory = factory.newThread(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            t3.set(current == null ? null : current.getTraceId());
        });
        fromFactory.start();
        fromFactory.join(3000);
        assertThat(fromFactory.getName()).startsWith("ctx-pool-");
        assertThat(t3.get()).isEqualTo(context.getTraceId());

        AtomicReference<String> t4 = new AtomicReference<String>();
        ThreadFactory wrappedFactory = LogCollectContextUtils.wrapThreadFactory(r -> new Thread(r, "raw"));
        Thread fromWrappedFactory = wrappedFactory.newThread(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            t4.set(current == null ? null : current.getTraceId());
        });
        fromWrappedFactory.start();
        fromWrappedFactory.join(3000);
        assertThat(t4.get()).isEqualTo(context.getTraceId());
    }

    @Test
    void supplyAsync_runAsync_propagate() throws Exception {
        CompletableFuture<String> supply = LogCollectContextUtils.supplyAsync(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            return current == null ? null : current.getTraceId();
        });
        assertThat(supply.get(3, TimeUnit.SECONDS)).isEqualTo(context.getTraceId());

        AtomicReference<String> trace = new AtomicReference<String>();
        CompletableFuture<Void> run = LogCollectContextUtils.runAsync(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            trace.set(current == null ? null : current.getTraceId());
        });
        run.get(3, TimeUnit.SECONDS);
        assertThat(trace.get()).isEqualTo(context.getTraceId());
    }

    @Test
    void wrapSupplier_and_wrapCallables_branchesCovered() {
        Supplier<String> wrappedSupplier = LogCollectContextUtils.wrapSupplier(() -> {
            LogCollectContext current = LogCollectContextManager.current();
            return current == null ? null : current.getTraceId();
        });
        assertThat(wrappedSupplier.get()).isEqualTo(context.getTraceId());

        assertThat(LogCollectContextUtils.wrapSupplier(null)).isNull();
        assertThat(LogCollectContextUtils.wrapCallables(null)).isEqualTo(Collections.emptyList());
    }

    @Test
    void isInLogCollectContext_and_diagnosticInfo_reflectCurrentState() {
        assertThat(LogCollectContextUtils.isInLogCollectContext()).isTrue();
        assertThat(LogCollectContextUtils.diagnosticInfo()).contains("depth=1");

        LogCollectContextManager.clear();
        assertThat(LogCollectContextUtils.isInLogCollectContext()).isFalse();
        assertThat(LogCollectContextUtils.diagnosticInfo()).isEqualTo("depth=0");
    }

    @Test
    void nullSafety_forAllWrapHelpers() {
        assertThat(LogCollectContextUtils.wrapExecutorService(null)).isNull();
        assertThat(LogCollectContextUtils.wrapScheduledExecutorService(null)).isNull();
        assertThat(LogCollectContextUtils.wrapExecutor(null)).isNull();
        assertThat(LogCollectContextUtils.wrapThreadFactory(null)).isNull();
        assertDoesNotThrow(() -> LogCollectContextUtils.newThread(() -> { }, "n"));
    }

    @Test
    void wrapExecutorService_wrapScheduledExecutorService_forAlreadyWrapped_returnsSame() {
        ExecutorService wrappedExecutor = new LogCollectExecutorServiceWrapper(Executors.newSingleThreadExecutor());
        try {
            assertThat(LogCollectContextUtils.wrapExecutorService(wrappedExecutor)).isSameAs(wrappedExecutor);
        } finally {
            wrappedExecutor.shutdownNow();
        }

        ScheduledExecutorService wrappedScheduled =
                new LogCollectScheduledExecutorServiceWrapper(Executors.newSingleThreadScheduledExecutor());
        try {
            assertThat(LogCollectContextUtils.wrapScheduledExecutorService(wrappedScheduled)).isSameAs(wrappedScheduled);
            assertThat(LogCollectContextUtils.wrapExecutorService(wrappedScheduled)).isSameAs(wrappedScheduled);
            assertThat(LogCollectContextUtils.wrapExecutor(wrappedScheduled)).isSameAs(wrappedScheduled);
        } finally {
            wrappedScheduled.shutdownNow();
        }
    }
}
