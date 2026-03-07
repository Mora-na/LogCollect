package com.logcollect.autoconfigure.reactive;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.context.LogCollectContextManager;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReactorContextPropagationConfigurerTest {

    @Test
    void coreSubscriber_onNext_shouldRestoreSnapshotAndClearAfterCallback() {
        String traceId = "trace-reactive";
        LogCollectContextManager.push(newContext(traceId));
        LogCollectContextSnapshot snapshot = LogCollectContextManager.captureSnapshot();
        LogCollectContextManager.clear();

        AtomicReference<String> seenTraceId = new AtomicReference<String>();
        CoreSubscriber<String> delegate = new CoreSubscriber<String>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                // no-op
            }

            @Override
            public void onNext(String ignored) {
                LogCollectContext current = LogCollectContextManager.current();
                seenTraceId.set(current == null ? null : current.getTraceId());
            }

            @Override
            public void onError(Throwable throwable) {
                // no-op
            }

            @Override
            public void onComplete() {
                // no-op
            }

            @Override
            public Context currentContext() {
                return Context.empty();
            }
        };

        ReactorContextPropagationConfigurer.LogCollectCoreSubscriber<String> subscriber =
                new ReactorContextPropagationConfigurer.LogCollectCoreSubscriber<String>(delegate, snapshot);

        subscriber.onNext("payload");
        assertThat(seenTraceId.get()).isEqualTo(traceId);
        assertThat(LogCollectContextManager.current()).isNull();
    }

    @Test
    void destroy_shouldResetHookWithoutThrowing() {
        ReactorContextPropagationConfigurer configurer = new ReactorContextPropagationConfigurer();
        try {
            configurer.destroy();
        } finally {
            LogCollectContextManager.clear();
        }
    }

    @Test
    void manualHook_shouldPropagateContextToScheduledTasks() throws Exception {
        ReactorContextPropagationConfigurer configurer = new ReactorContextPropagationConfigurer();
        Method method = ReactorContextPropagationConfigurer.class.getDeclaredMethod("installManualPropagationHook");
        method.setAccessible(true);

        Scheduler scheduler = Schedulers.newSingle("reactive-hook-test");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> seenTraceId = new AtomicReference<String>();

        LogCollectContextManager.push(newContext("trace-reactor-scheduler"));
        try {
            method.invoke(configurer);
            Disposable disposable = scheduler.schedule(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                seenTraceId.set(current == null ? null : current.getTraceId());
                latch.countDown();
            });
            assertThat(disposable.isDisposed()).isFalse();
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seenTraceId.get()).isEqualTo("trace-reactor-scheduler");
        } finally {
            scheduler.dispose();
            configurer.destroy();
            LogCollectContextManager.clear();
        }
    }

    @Test
    void manualHook_shouldPropagateContextAcrossPublishOnPipelines() throws Exception {
        ReactorContextPropagationConfigurer configurer = new ReactorContextPropagationConfigurer();
        Method method = ReactorContextPropagationConfigurer.class.getDeclaredMethod("installManualPropagationHook");
        method.setAccessible(true);

        AtomicReference<String> monoTraceId = new AtomicReference<String>();
        AtomicReference<String> fluxTraceId = new AtomicReference<String>();

        LogCollectContextManager.push(newContext("trace-reactor-publishOn"));
        try {
            method.invoke(configurer);

            String monoValue = Mono.just("mono")
                    .publishOn(Schedulers.boundedElastic())
                    .map(value -> {
                        LogCollectContext current = LogCollectContextManager.current();
                        monoTraceId.set(current == null ? null : current.getTraceId());
                        return value;
                    })
                    .block();

            Integer fluxCount = Flux.just(1, 2, 3)
                    .publishOn(Schedulers.parallel())
                    .map(value -> {
                        LogCollectContext current = LogCollectContextManager.current();
                        fluxTraceId.compareAndSet(null, current == null ? null : current.getTraceId());
                        return value;
                    })
                    .count()
                    .map(Long::intValue)
                    .block();

            assertThat(monoValue).isEqualTo("mono");
            assertThat(fluxCount).isEqualTo(3);
            assertThat(monoTraceId.get()).isEqualTo("trace-reactor-publishOn");
            assertThat(fluxTraceId.get()).isEqualTo("trace-reactor-publishOn");
        } finally {
            configurer.destroy();
            LogCollectContextManager.clear();
        }
    }

    private static LogCollectContext newContext(String traceId) {
        return new LogCollectContext(traceId, null, null, null, null, null, null, null);
    }
}
