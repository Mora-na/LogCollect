package com.logcollect.autoconfigure.servlet;

import com.logcollect.api.model.LogCollectContextSnapshot;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LogCollectServletAsyncSupport {

    private static final String ATTR_ASYNC_STATE =
            LogCollectServletAsyncSupport.class.getName() + ".ASYNC_STATE";

    private static final ThreadLocal<Object> CURRENT_REQUEST = new ThreadLocal<Object>();

    private LogCollectServletAsyncSupport() {
    }

    public static void bindCurrentRequest(Object request) {
        if (request == null) {
            CURRENT_REQUEST.remove();
            return;
        }
        CURRENT_REQUEST.set(request);
    }

    public static void clearCurrentRequest() {
        CURRENT_REQUEST.remove();
    }

    public static Object currentRequest() {
        return CURRENT_REQUEST.get();
    }

    public static boolean isAsyncStarted(Object request) {
        Object result = invoke(request, "isAsyncStarted");
        return result instanceof Boolean && ((Boolean) result).booleanValue();
    }

    public static AsyncState getAsyncState(Object request) {
        Object state = getAttribute(request, ATTR_ASYNC_STATE);
        return state instanceof AsyncState ? (AsyncState) state : null;
    }

    public static AsyncState getOrCreateAsyncState(Object request) {
        if (request == null) {
            return null;
        }
        AsyncState existing = getAsyncState(request);
        if (existing != null) {
            return existing;
        }
        AsyncState created = new AsyncState();
        setAttribute(request, ATTR_ASYNC_STATE, created);
        return created;
    }

    public static Object getAttribute(Object request, String attributeName) {
        return invoke(request, "getAttribute", new Class<?>[]{String.class}, new Object[]{attributeName});
    }

    public static void setAttribute(Object request, String attributeName, Object value) {
        invoke(request, "setAttribute", new Class<?>[]{String.class, Object.class}, new Object[]{attributeName, value});
    }

    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object[] arguments) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, arguments);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to invoke servlet method: " + methodName, e);
        }
    }

    public static final class AsyncState {
        private final AtomicReference<LogCollectContextSnapshot> snapshotRef =
                new AtomicReference<LogCollectContextSnapshot>(LogCollectContextSnapshot.EMPTY);
        private final AtomicReference<LogCollectServletAsyncFinalizer> finalizerRef =
                new AtomicReference<LogCollectServletAsyncFinalizer>();
        private final AtomicReference<Throwable> completionErrorRef = new AtomicReference<Throwable>();
        private final AtomicBoolean completed = new AtomicBoolean(false);

        public void updateSnapshot(LogCollectContextSnapshot snapshot) {
            if (snapshot == null || snapshot.isEmpty()) {
                return;
            }
            snapshotRef.set(snapshot);
        }

        public LogCollectContextSnapshot snapshot() {
            return snapshotRef.get();
        }

        public void registerFinalizer(LogCollectServletAsyncFinalizer finalizer) {
            if (finalizer == null) {
                return;
            }
            if (!finalizerRef.compareAndSet(null, finalizer)) {
                return;
            }
            if (completed.get()) {
                finalizer.finish(completionErrorRef.get());
            }
        }

        public void finish(Throwable error) {
            if (error != null) {
                completionErrorRef.compareAndSet(null, error);
            }
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            LogCollectServletAsyncFinalizer finalizer = finalizerRef.get();
            if (finalizer != null) {
                finalizer.finish(completionErrorRef.get());
            }
        }
    }
}
