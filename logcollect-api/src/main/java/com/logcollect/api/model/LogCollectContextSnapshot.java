package com.logcollect.api.model;

import java.util.*;

/**
 * {@link LogCollectContext} 的可传递快照。
 *
 * <p>用于将当前线程的上下文栈与 MDC 拷贝到异步线程中恢复执行。
 * 快照对象本身是只读语义：构造时复制入参，读取时返回副本，避免外部修改内部状态。
 */
public final class LogCollectContextSnapshot {
    /** 空快照常量，表示当前线程不存在活跃 LogCollect 上下文。 */
    public static final LogCollectContextSnapshot EMPTY =
            new LogCollectContextSnapshot(new ArrayDeque<LogCollectContext>(), Collections.<String, String>emptyMap());

    /** 冻结后的上下文栈快照（栈顶为当前上下文）。 */
    private final Deque<LogCollectContext> frozenStack;
    /** 冻结后的 MDC 上下文快照。 */
    private final Map<String, String> mdcSnapshot;

    /**
     * 构造上下文快照。
     *
     * @param stack       当前线程上下文栈
     * @param mdcSnapshot 当前线程 MDC 快照
     */
    public LogCollectContextSnapshot(Deque<LogCollectContext> stack, Map<String, String> mdcSnapshot) {
        this.frozenStack = new ArrayDeque<LogCollectContext>(stack);
        this.mdcSnapshot = new HashMap<String, String>(mdcSnapshot);
    }

    /**
     * 获取上下文栈副本。
     *
     * @return 上下文栈副本
     */
    public Deque<LogCollectContext> getFrozenStack() {
        return new ArrayDeque<LogCollectContext>(frozenStack);
    }

    /**
     * 获取 MDC 快照副本。
     *
     * @return MDC 快照副本
     */
    public Map<String, String> getMdcSnapshot() {
        return new HashMap<String, String>(mdcSnapshot);
    }

    /**
     * 获取快照中的当前上下文（栈顶元素）。
     *
     * @return 当前上下文；若栈空返回 null
     */
    public LogCollectContext currentContext() {
        return frozenStack.peek();
    }

    /**
     * 便捷获取快照中的 traceId。
     *
     * @return traceId；若栈空返回 null
     */
    public String getTraceId() {
        LogCollectContext ctx = currentContext();
        return ctx == null ? null : ctx.getTraceId();
    }

    /**
     * @return true 表示快照中无上下文信息
     */
    public boolean isEmpty() {
        return frozenStack.isEmpty();
    }
}
