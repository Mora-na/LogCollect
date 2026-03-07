package com.logcollect.autoconfigure.reactive;

import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.context.LogCollectContextUtils;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

@Configuration
@ConditionalOnClass(name = "reactor.core.publisher.Hooks")
public class ReactorContextPropagationConfigurer implements InitializingBean, DisposableBean {
    private static final String HOOK_KEY = "logcollect-context-propagation";
    private static final String CONTEXT_KEY = HOOK_KEY + ".snapshot";
    private static final Object LIFECYCLE_LOCK = new Object();

    private static int activeRegistrations;
    private static PropagationMode installedMode = PropagationMode.NONE;

    private PropagationMode registeredMode = PropagationMode.NONE;

    enum PropagationMode {
        NONE,
        MANUAL,
        AUTOMATIC
    }

    @Override
    public void afterPropertiesSet() {
        try {
            registerPropagation(hasAutomaticPropagation() ? PropagationMode.AUTOMATIC : PropagationMode.MANUAL);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn(
                    "Reactor context propagation setup failed, WebFlux context propagation may not work", t);
        }
    }

    @Override
    public void destroy() {
        unregisterPropagation();
    }

    private boolean hasAutomaticPropagation() {
        try {
            Hooks.class.getMethod("enableAutomaticContextPropagation");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    void registerPropagation(PropagationMode desiredMode) {
        if (desiredMode == null || desiredMode == PropagationMode.NONE) {
            return;
        }

        synchronized (LIFECYCLE_LOCK) {
            if (registeredMode != PropagationMode.NONE) {
                return;
            }

            if (activeRegistrations == 0) {
                installedMode = installGlobalPropagation(desiredMode);
            }

            activeRegistrations++;
            registeredMode = installedMode;
        }
    }

    void unregisterPropagation() {
        synchronized (LIFECYCLE_LOCK) {
            if (registeredMode == PropagationMode.NONE) {
                return;
            }

            activeRegistrations--;
            if (activeRegistrations <= 0) {
                activeRegistrations = 0;
                resetGlobalPropagation(installedMode);
                installedMode = PropagationMode.NONE;
            }

            registeredMode = PropagationMode.NONE;
        }
    }

    private PropagationMode installGlobalPropagation(PropagationMode desiredMode) {
        if (desiredMode == PropagationMode.AUTOMATIC) {
            try {
                enableAutomaticPropagation();
                return PropagationMode.AUTOMATIC;
            } catch (Throwable t) {
                LogCollectInternalLogger.warn(
                        "Failed to enable automatic propagation, falling back to manual hook", t);
            }
        }

        installManualPropagationHook();
        return PropagationMode.MANUAL;
    }

    private void resetGlobalPropagation(PropagationMode mode) {
        if (mode == PropagationMode.MANUAL) {
            resetManualPropagationHook();
            return;
        }
        if (mode == PropagationMode.AUTOMATIC) {
            disableAutomaticPropagation();
        }
    }

    private void enableAutomaticPropagation() throws Exception {
        try {
            Hooks.class.getMethod("enableAutomaticContextPropagation").invoke(null);
            LogCollectInternalLogger.info(
                    "Reactor 3.5.3+ detected: automatic context propagation enabled");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable target = e.getTargetException() == null ? e : e.getTargetException();
            if (target instanceof Exception) {
                throw (Exception) target;
            }
            throw e;
        }
    }

    private void installManualPropagationHook() {
        Hooks.onEachOperator(HOOK_KEY,
                Operators.lift((scannable, subscriber) -> {
                    LogCollectContextSnapshot snapshot = resolveSnapshot(subscriber.currentContext());
                    if (snapshot == null || snapshot.isEmpty()) {
                        return subscriber;
                    }
                    return new LogCollectCoreSubscriber<Object>(subscriber, snapshot);
                }));
        Schedulers.onScheduleHook(HOOK_KEY, runnable -> LogCollectContextUtils.wrapRunnable(runnable));
        Schedulers.setExecutorServiceDecorator(HOOK_KEY,
                (scheduler, executor) -> LogCollectContextUtils.wrapScheduledExecutorService(executor));
        Schedulers.resetFactory();
        LogCollectInternalLogger.info(
                "Reactor 3.4.x detected: manual context propagation hook installed");
    }

    private void resetManualPropagationHook() {
        try {
            Hooks.resetOnEachOperator(HOOK_KEY);
        } catch (Throwable ignore) {
        }
        try {
            Schedulers.resetOnScheduleHook(HOOK_KEY);
        } catch (Throwable ignore) {
        }
        try {
            Schedulers.removeExecutorServiceDecorator(HOOK_KEY);
        } catch (Throwable ignore) {
        }
        try {
            Schedulers.resetFactory();
        } catch (Throwable ignore) {
        }
    }

    private void disableAutomaticPropagation() {
        try {
            Hooks.class.getMethod("disableAutomaticContextPropagation").invoke(null);
        } catch (NoSuchMethodException ignore) {
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Failed to disable automatic Reactor context propagation", t);
        }
    }

    private LogCollectContextSnapshot resolveSnapshot(Context context) {
        if (context != null) {
            Object existing = context.getOrDefault(CONTEXT_KEY, null);
            if (existing instanceof LogCollectContextSnapshot) {
                LogCollectContextSnapshot snapshot = (LogCollectContextSnapshot) existing;
                if (!snapshot.isEmpty()) {
                    return snapshot;
                }
            }
        }
        LogCollectContextSnapshot currentSnapshot = LogCollectContextManager.captureSnapshot();
        return currentSnapshot.isEmpty() ? null : currentSnapshot;
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
            return delegate.currentContext().put(CONTEXT_KEY, snapshot);
        }

        private void restoreAndRun(Runnable action) {
            LogCollectContextSnapshot previous = LogCollectContextManager.captureSnapshot();
            LogCollectContextManager.restoreSnapshot(snapshot);
            try {
                action.run();
            } finally {
                LogCollectContextManager.restoreSnapshot(previous);
            }
        }
    }
}
