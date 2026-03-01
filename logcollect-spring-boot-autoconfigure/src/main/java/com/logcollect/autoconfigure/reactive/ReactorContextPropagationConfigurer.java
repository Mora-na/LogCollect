package com.logcollect.autoconfigure.reactive;

import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

@Configuration
@ConditionalOnClass(name = "reactor.core.publisher.Hooks")
public class ReactorContextPropagationConfigurer implements InitializingBean, DisposableBean {
    private static final String HOOK_KEY = "logcollect-context-propagation";

    @Override
    public void afterPropertiesSet() {
        try {
            if (hasAutomaticPropagation()) {
                enableAutomaticPropagation();
            } else {
                installManualPropagationHook();
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn(
                    "Reactor context propagation setup failed, WebFlux context propagation may not work", t);
        }
    }

    @Override
    public void destroy() {
        try {
            Hooks.resetOnEachOperator(HOOK_KEY);
        } catch (Throwable ignore) {
        }
    }

    private boolean hasAutomaticPropagation() {
        try {
            Hooks.class.getMethod("enableAutomaticContextPropagation");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private void enableAutomaticPropagation() {
        try {
            Hooks.class.getMethod("enableAutomaticContextPropagation").invoke(null);
            LogCollectInternalLogger.info(
                    "Reactor 3.5.3+ detected: automatic context propagation enabled");
        } catch (Throwable t) {
            LogCollectInternalLogger.warn(
                    "Failed to enable automatic propagation, falling back to manual hook", t);
            installManualPropagationHook();
        }
    }

    private void installManualPropagationHook() {
        Hooks.onEachOperator(HOOK_KEY,
                Operators.lift((scannable, subscriber) -> {
                    if (LogCollectContextManager.current() == null) {
                        return subscriber;
                    }
                    LogCollectContextSnapshot snapshot = LogCollectContextManager.captureSnapshot();
                    return new LogCollectCoreSubscriber<Object>(subscriber, snapshot);
                }));
        LogCollectInternalLogger.info(
                "Reactor 3.4.x detected: manual context propagation hook installed");
    }

    static class LogCollectCoreSubscriber<T> implements CoreSubscriber<T> {
        private final CoreSubscriber<T> delegate;
        private final LogCollectContextSnapshot snapshot;

        LogCollectCoreSubscriber(CoreSubscriber<T> delegate, LogCollectContextSnapshot snapshot) {
            this.delegate = delegate;
            this.snapshot = snapshot;
        }

        @Override
        public void onSubscribe(Subscription s) {
            restoreAndRun(() -> delegate.onSubscribe(s));
        }

        @Override
        public void onNext(T t) {
            restoreAndRun(() -> delegate.onNext(t));
        }

        @Override
        public void onError(Throwable t) {
            restoreAndRun(() -> delegate.onError(t));
        }

        @Override
        public void onComplete() {
            restoreAndRun(delegate::onComplete);
        }

        @Override
        public Context currentContext() {
            return delegate.currentContext();
        }

        private void restoreAndRun(Runnable action) {
            LogCollectContextManager.restoreSnapshot(snapshot);
            try {
                action.run();
            } finally {
                LogCollectContextManager.clearSnapshotContext();
            }
        }
    }
}
