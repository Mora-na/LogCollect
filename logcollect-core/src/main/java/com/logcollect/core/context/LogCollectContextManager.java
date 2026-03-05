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
    private static final Map<String, LogCollectContext> ACTIVE_CONTEXTS =
            new ConcurrentHashMap<String, LogCollectContext>();

    private LogCollectContextManager() {
    }

    public static void push(LogCollectContext context) {
        if (context == null) {
            return;
        }
        CONTEXT_STACK.get().push(context);
        if (context.getTraceId() != null) {
            ACTIVE_CONTEXTS.put(context.getTraceId(), context);
            MDCAdapter.put(TRACE_ID_KEY, context.getTraceId());
        }
    }

    public static LogCollectContext pop() {
        Deque<LogCollectContext> stack = CONTEXT_STACK.get();
        LogCollectContext popped = stack.poll();
        if (popped != null && popped.getTraceId() != null) {
            ACTIVE_CONTEXTS.remove(popped.getTraceId(), popped);
        }
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
        return ACTIVE_CONTEXTS.get(traceId);
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
        if (existing != null) {
            for (LogCollectContext context : existing) {
                if (context != null && context.getTraceId() != null) {
                    ACTIVE_CONTEXTS.remove(context.getTraceId(), context);
                }
            }
        }
        CONTEXT_STACK.set(new ArrayDeque<LogCollectContext>(snapshot));
        rebuildActiveContexts(snapshot);
        LogCollectContext current = current();
        if (current != null && current.getTraceId() != null) {
            MDCAdapter.put(TRACE_ID_KEY, current.getTraceId());
        } else {
            MDCAdapter.remove(TRACE_ID_KEY);
        }
    }

    public static void clear() {
        Deque<LogCollectContext> existing = CONTEXT_STACK.get();
        if (existing != null) {
            for (LogCollectContext context : existing) {
                if (context != null && context.getTraceId() != null) {
                    ACTIVE_CONTEXTS.remove(context.getTraceId(), context);
                }
            }
        }
        CONTEXT_STACK.remove();
        MDCAdapter.remove(TRACE_ID_KEY);
        LogCollectIgnoreManager.clear();
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

    private static void rebuildActiveContexts(Deque<LogCollectContext> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        for (LogCollectContext context : snapshot) {
            if (context == null || context.getTraceId() == null) {
                continue;
            }
            ACTIVE_CONTEXTS.put(context.getTraceId(), context);
        }
    }
}
