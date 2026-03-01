package com.logcollect.core.buffer;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;

/**
 * 日志缓冲区抽象接口。
 *
 * <p>不同收集模式（SINGLE / AGGREGATE）对应不同缓冲实现，
 * 但都遵循“入队 -> 条件触发 flush -> 结束时强制 flush”的统一契约。
 */
public interface LogCollectBuffer {
    /**
     * 尝试放入一条日志。
     *
     * @param context 当前调用上下文
     * @param entry   日志条目
     * @return true 表示入队成功；false 表示入队失败（通常用于触发降级统计）
     */
    boolean offer(LogCollectContext context, LogEntry entry);

    /**
     * 触发一次 flush。
     *
     * @param context 当前调用上下文
     * @param isFinal 是否最终 flush（方法即将结束）
     */
    void triggerFlush(LogCollectContext context, boolean isFinal);

    /**
     * 关闭并强制 flush 剩余日志。
     *
     * @param context 当前调用上下文
     */
    void closeAndFlush(LogCollectContext context);

    /**
     * 生命周期强制刷盘入口。
     */
    default void forceFlush() {
        triggerFlush(null, true);
    }

    /**
     * 调试/降级用文本快照。
     */
    default String dumpAsString() {
        return "";
    }
}
