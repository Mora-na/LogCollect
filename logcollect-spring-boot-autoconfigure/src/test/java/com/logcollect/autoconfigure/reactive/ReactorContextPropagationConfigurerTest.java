package com.logcollect.autoconfigure.reactive;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.context.LogCollectContextManager;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

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

    private static LogCollectContext newContext(String traceId) {
        return new LogCollectContext(traceId, null, null, null, null, null, null, null);
    }
}
