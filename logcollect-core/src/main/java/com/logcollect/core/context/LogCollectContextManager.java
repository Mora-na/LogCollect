package com.logcollect.core.context;

import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.mdc.MDCAdapter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * LogCollect 上下文栈管理器。
 *
 * <p>职责：
 * 管理线程内上下文栈（支持嵌套调用）、快照捕获与恢复、MDC traceId 同步维护。
 * 该类是上下文传播链路的核心组件，供 AOP、线程包装器、ContextPropagation 适配层调用。
 */
public final class LogCollectContextManager {
    /** 当前线程的 LogCollect 上下文栈。 */
    private static final ThreadLocal<Deque<LogCollectContext>> CONTEXT_STACK = new ThreadLocal<Deque<LogCollectContext>>();
    /** 标记当前线程是否处于“快照恢复模式”（预留标识，便于诊断扩展）。 */
    private static final ThreadLocal<Boolean> IS_SNAPSHOT_MODE = new ThreadLocal<Boolean>();
    /** 写入 MDC 的 traceId 键名。 */
    private static final String MDC_KEY = "_logCollect_traceId";

    private LogCollectContextManager() {}

    /**
     * 将上下文压入当前线程栈顶，并同步 MDC。
     *
     * @param ctx 待压栈上下文
     */
    public static void push(LogCollectContext ctx) {
        if (ctx == null) {
            return;
        }
        Deque<LogCollectContext> stack = CONTEXT_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<LogCollectContext>();
            CONTEXT_STACK.set(stack);
        }
        LogCollectConfig config = ctx.getConfig();
        int maxDepth = config == null ? 10 : config.getMaxNestingDepth();
        if (stack.size() >= maxDepth) {
            LogCollectInternalLogger.warn("Max nesting depth {} reached, skip push", maxDepth);
            return;
        }
        stack.push(ctx);
        MDCAdapter.put(MDC_KEY, ctx.getTraceId());
    }

    /**
     * 从当前线程栈顶弹出上下文，并校验 traceId 一致性。
     *
     * <p>若发现 traceId 不一致，说明上下文已错位（通常由异常链路或误用导致），
     * 会触发强制清理，避免污染后续线程任务。
     *
     * @param expectedTraceId 预期弹出的 traceId
     * @return 弹出的上下文；若无上下文或校验失败返回 null
     */
    public static LogCollectContext pop(String expectedTraceId) {
        Deque<LogCollectContext> stack = CONTEXT_STACK.get();
        if (stack == null || stack.isEmpty()) {
            MDCAdapter.remove(MDC_KEY);
            CONTEXT_STACK.remove();
            return null;
        }
        LogCollectContext top = stack.peek();
        if (top != null && expectedTraceId != null && !expectedTraceId.equals(top.getTraceId())) {
            LogCollectInternalLogger.warn("TraceId mismatch, force cleanup. expected={}, actual={}", expectedTraceId, top.getTraceId());
            forceCleanup();
            return null;
        }
        LogCollectContext popped = stack.pop();
        if (stack.isEmpty()) {
            CONTEXT_STACK.remove();
            MDCAdapter.remove(MDC_KEY);
        } else {
            LogCollectContext next = stack.peek();
            if (next != null) {
                MDCAdapter.put(MDC_KEY, next.getTraceId());
            }
        }
        return popped;
    }

    /**
     * 获取当前线程栈顶上下文。
     *
     * @return 当前上下文；若无则返回 null
     */
    public static LogCollectContext current() {
        Deque<LogCollectContext> stack = CONTEXT_STACK.get();
        return stack == null ? null : stack.peek();
    }

    /**
     * 获取当前线程上下文栈深度。
     *
     * @return 栈深度
     */
    public static int depth() {
        Deque<LogCollectContext> stack = CONTEXT_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    /**
     * 捕获当前线程快照（上下文栈 + MDC）。
     *
     * @return 快照对象；若当前无上下文返回 {@link LogCollectContextSnapshot#EMPTY}
     */
    public static LogCollectContextSnapshot captureSnapshot() {
        Deque<LogCollectContext> stack = CONTEXT_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return LogCollectContextSnapshot.EMPTY;
        }
        Map<String, String> mdcSnapshot = MDCAdapter.getCopyOfContextMap();
        return new LogCollectContextSnapshot(stack, mdcSnapshot);
    }

    /**
     * 在当前线程恢复快照内容。
     *
     * @param snapshot 待恢复快照
     */
    public static void restoreSnapshot(LogCollectContextSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        CONTEXT_STACK.set(new ArrayDeque<LogCollectContext>(snapshot.getFrozenStack()));
        IS_SNAPSHOT_MODE.set(Boolean.TRUE);
        MDCAdapter.setContextMap(snapshot.getMdcSnapshot());
    }

    /**
     * 清空当前线程上下文与 MDC。
     *
     * <p>通常在异步任务 finally 中调用，确保线程复用时不会残留上下文。
     */
    public static void clearSnapshotContext() {
        CONTEXT_STACK.remove();
        IS_SNAPSHOT_MODE.remove();
        MDCAdapter.remove(MDC_KEY);
    }

    /**
     * 发生上下文错位时执行的保护性清理。
     */
    private static void forceCleanup() {
        clearSnapshotContext();
        LogCollectInternalLogger.warn("LogCollect context forcibly cleaned");
    }
}
