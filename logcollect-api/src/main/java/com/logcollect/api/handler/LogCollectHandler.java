package com.logcollect.api.handler;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.DegradeEvent;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;

/**
 * 日志收集处理器接口。
 *
 * <p>业务方实现该接口，用于定义日志最终如何持久化（数据库、MQ、文件等）。
 *
 * <p>框架支持两种收集模式：
 * <ul>
 *   <li>SINGLE：逐条回调 {@link #appendLog(LogCollectContext, LogEntry)}</li>
 *   <li>AGGREGATE：批量回调 {@link #flushAggregatedLog(LogCollectContext, AggregatedLog)}</li>
 * </ul>
 *
 * <p>模式决策优先级（与 README 一致）：
 * 合并配置中的 {@code collectMode} > {@link #preferredMode()} > 框架默认模式。
 */
public interface LogCollectHandler {

    // =====================================================================
    // 生命周期
    // =====================================================================

    /**
     * 业务方法执行前回调。
     *
     * <p>典型职责：
     * 初始化业务主记录、设置 {@code context.businessId}、写入共享属性。
     *
     * @param context 本次调用上下文（已包含 traceId、方法信息、配置等）
     */
    default void before(LogCollectContext context) {}

    /**
     * 业务方法执行后回调（缓冲区已 flush）。
     *
     * <p>进入该回调时，框架已尝试 flush 剩余缓冲，context 中可读取完整结果：
     * 返回值/异常、耗时、统计计数、业务属性等。
     *
     * @param context 本次调用上下文
     */
    default void after(LogCollectContext context) {}

    // =====================================================================
    // 模式1：SINGLE
    // =====================================================================

    /**
     * 逐条日志回调（SINGLE 模式）。
     *
     * <p>默认实现抛 {@link UnsupportedOperationException}，
     * 用于强制在 SINGLE 模式下显式实现该方法。
     *
     * @param context 本次调用上下文
     * @param entry   单条日志对象（内容已完成净化与脱敏）
     */
    default void appendLog(LogCollectContext context, LogEntry entry) {
        throw new UnsupportedOperationException("当前使用 SINGLE 模式，须实现 appendLog()。如需聚合模式，请实现 flushAggregatedLog() 并设置 collectMode=AGGREGATE");
    }

    // =====================================================================
    // 模式2：AGGREGATE
    // =====================================================================

    /**
     * 聚合日志回调（AGGREGATE 模式）。
     *
     * <p>默认实现抛 {@link UnsupportedOperationException}，
     * 用于强制在 AGGREGATE 模式下显式实现该方法。
     *
     * <p>当日志量大于阈值时可能被调用多次，可通过
     * {@link AggregatedLog#isFinalFlush()} 判断是否最后一次刷写。
     *
     * @param context       本次调用上下文
     * @param aggregatedLog 聚合日志块（已拼接完成）
     */
    default void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        throw new UnsupportedOperationException("当前使用 AGGREGATE 模式，须实现 flushAggregatedLog()。如需单条模式，请实现 appendLog() 并设置 collectMode=SINGLE");
    }

    // =====================================================================
    // 格式化
    // =====================================================================

    /**
     * 将单条日志格式化为一行（仅 AGGREGATE 模式使用）。
     *
     * <p>该方法会在日志进入聚合缓冲前调用。建议输出稳定格式，便于后续审计检索。
     *
     * @param entry 已净化/脱敏后的日志条目
     * @return 格式化后的日志行
     */
    default String formatLogLine(LogEntry entry) {
        return String.format("[%s] [%-5s] [%s] %s",
                entry.getTime(),
                entry.getLevel(),
                entry.getThreadName(),
                entry.getContent());
    }

    /**
     * 聚合日志块中行与行之间的分隔符。
     *
     * @return 分隔符字符串，默认换行符
     */
    default String aggregatedLogSeparator() {
        return "\n";
    }

    // =====================================================================
    // 过滤
    // =====================================================================

    /**
     * 自定义过滤钩子：级别过滤之后、净化/脱敏之前执行。
     *
     * <p>返回 {@code false} 可直接丢弃当前日志，减少后续处理开销。
     *
     * @param context    本次调用上下文
     * @param level      日志级别
     * @param rawMessage 原始日志内容
     * @return true 表示采集，false 表示丢弃
     */
    default boolean shouldCollect(LogCollectContext context, String level, String rawMessage) {
        return true;
    }

    // =====================================================================
    // 模式偏好
    // =====================================================================

    /**
     * 当注解使用 AUTO 时的模式偏好。
     *
     * @return 偏好模式；返回 {@link CollectMode#AUTO} 表示使用框架默认模式
     */
    default CollectMode preferredMode() {
        return CollectMode.AUTO;
    }

    // =====================================================================
    // 降级 / 错误回调
    // =====================================================================

    /**
     * 降级/丢弃事件回调。
     *
     * <p>可在此处记录告警、统计降级原因或触发补偿逻辑。
     *
     * @param context 本次调用上下文（若上下文尚未建立可能为 null）
     * @param event   降级事件详情
     */
    default void onDegrade(LogCollectContext context, DegradeEvent event) {}

    /**
     * 处理器方法异常回调。
     *
     * <p>框架会捕获处理器抛出的异常并回调本方法，避免业务主流程被日志异常中断。
     *
     * @param context 本次调用上下文
     * @param error   异常对象
     * @param phase   出错阶段（如 before/after/appendLog/flushAggregatedLog/formatLogLine）
     */
    default void onError(LogCollectContext context, Throwable error, String phase) {}
}
