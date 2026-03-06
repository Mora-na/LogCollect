package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.core.test.CoreUnitTestBase;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectForkJoinContextPropagationTest extends CoreUnitTestBase {

    @Test
    void wrapRunnable_withForkJoinPool_shouldPropagateContext() throws Exception {
        LogCollectContext context = createTestContext();
        LogCollectContextManager.push(context);

        AtomicReference<String> childTraceId = new AtomicReference<String>();
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            pool.submit(LogCollectContextUtils.wrapRunnable(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                childTraceId.set(current == null ? null : current.getTraceId());
            })).get(3, TimeUnit.SECONDS);
            assertThat(childTraceId.get()).isEqualTo(context.getTraceId());
        } finally {
            pool.shutdownNow();
            LogCollectContextManager.pop();
        }
    }

    @Test
    void wrapSupplier_withParallelStreamEntry_shouldKeepContext() throws Exception {
        LogCollectContext context = createTestContext();
        LogCollectContextManager.push(context);

        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            Supplier<String> wrappedSupplier = LogCollectContextUtils.wrapSupplier(() -> {
                IntStream.range(0, 64).parallel().sum();
                LogCollectContext current = LogCollectContextManager.current();
                return current == null ? null : current.getTraceId();
            });
            String childTraceId = pool.submit(wrappedSupplier::get).get(3, TimeUnit.SECONDS);
            assertThat(childTraceId).isEqualTo(context.getTraceId());
        } finally {
            pool.shutdownNow();
            LogCollectContextManager.pop();
        }
    }
}
