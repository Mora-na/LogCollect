package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.mdc.MDCAdapter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LogCollectContextManager {

    public static final String TRACE_ID_KEY = "_logCollect_traceId";

    private static final ThreadLocal<Deque<LogCollectContext>> CONTEXT_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final Map<String, ActiveContextRef> ACTIVE_CONTEXTS =
            new ConcurrentHashMap<String, ActiveContextRef>();

    private static final class ActiveContextRef {
        private final LogCollectContext context;
        private int references;

        private ActiveContextRef(LogCollectContext context) {
            this.context = context;
            this.references = 1;
        }

        private LogCollectContext getContext() {
            return context;
        }

        private void retain() {
            references++;
        }

        private int release() {
            references--;
            return references;
        }

        private boolean matches(LogCollectContext candidate) {
            return context == candidate;
        }
    }

    private LogCollectContextManager() {
    }

    public static void push(LogCollectContext context) {
        if (context == null) {
            return;
        }
        CONTEXT_STACK.get().push(context);
        retainContext(context);
        if (context.getTraceId() != null) {
            MDCAdapter.put(TRACE_ID_KEY, context.getTraceId());
        }
    }

    public static LogCollectContext pop() {
        Deque<LogCollectContext> stack = CONTEXT_STACK.get();
        LogCollectContext popped = stack.poll();
        releaseContext(popped);
        if (stack.isEmpty()) {
            CONTEXT_STACK.remove();
            MDCAdapter.remove(TRACE_ID_KEY);
        } else {
            LogCollectContext next = stack.peek();
            if (next != null && next.getTraceId() != null) {
                MDCAdapter.put(TRACE_ID_KEY, next.getTraceId());
            }
        }
        return popped;
    }

    public static LogCollectContext current() {
        Deque<LogCollectContext> stack = CONTEXT_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    public static LogCollectContext get(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return null;
        }
        ActiveContextRef active = ACTIVE_CONTEXTS.get(traceId);
        return active == null ? null : active.getContext();
    }

    public static int depth() {
        return CONTEXT_STACK.get().size();
    }

    public static Deque<LogCollectContext> snapshot() {
        return new ArrayDeque<LogCollectContext>(CONTEXT_STACK.get());
    }

    public static void restore(Deque<LogCollectContext> snapshot) {
        if (snapshot == null) {
            clear();
            return;
        }
        Deque<LogCollectContext> existing = CONTEXT_STACK.get();
        releaseContexts(existing);
        CONTEXT_STACK.set(new ArrayDeque<LogCollectContext>(snapshot));
        retainContexts(snapshot);
        LogCollectContext current = current();
        if (current != null && current.getTraceId() != null) {
            MDCAdapter.put(TRACE_ID_KEY, current.getTraceId());
        } else {
            MDCAdapter.remove(TRACE_ID_KEY);
        }
    }

    public static void clear() {
        Deque<LogCollectContext> existing = CONTEXT_STACK.get();
        releaseContexts(existing);
        CONTEXT_STACK.remove();
        MDCAdapter.remove(TRACE_ID_KEY);
        LogCollectIgnoreManager.clear();
    }

    public static void retain(LogCollectContext context) {
        retainContext(context);
    }

    public static void release(LogCollectContext context) {
        releaseContext(context);
    }

    public static LogCollectContextSnapshot captureSnapshot() {
        return new LogCollectContextSnapshot(snapshot(), MDCAdapter.getCopyOfContextMap());
    }

    public static void restoreSnapshot(LogCollectContextSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            clear();
            return;
        }
        restore(snapshot.getFrozenStack());
        MDCAdapter.setContextMap(snapshot.getMdcSnapshot());
    }

    public static void clearSnapshotContext() {
        clear();
    }

    private static void retainContexts(Deque<LogCollectContext> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        for (LogCollectContext context : snapshot) {
            retainContext(context);
        }
    }

    private static void releaseContexts(Deque<LogCollectContext> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        for (LogCollectContext context : snapshot) {
            releaseContext(context);
        }
    }

    private static void retainContext(LogCollectContext context) {
        if (context == null || context.getTraceId() == null) {
            return;
        }
        ACTIVE_CONTEXTS.compute(context.getTraceId(), (traceId, existing) -> {
            if (existing == null || !existing.matches(context)) {
                return new ActiveContextRef(context);
            }
            existing.retain();
            return existing;
        });
    }

    private static void releaseContext(LogCollectContext context) {
        if (context == null || context.getTraceId() == null) {
            return;
        }
        ACTIVE_CONTEXTS.computeIfPresent(context.getTraceId(), (traceId, existing) -> {
            if (!existing.matches(context)) {
                return existing;
            }
            return existing.release() <= 0 ? null : existing;
        });
    }
}
