package com.logcollect.api.handler;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.format.LogLineDefaults;
import com.logcollect.api.format.LogLinePatternParser;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.DegradeEvent;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 日志收集处理器接口。
 */
public interface LogCollectHandler {

    // =====================================================================
    // 生命周期
    // =====================================================================

    default void before(LogCollectContext context) {
    }

    default void after(LogCollectContext context) {
    }

    // =====================================================================
    // 模式1：SINGLE
    // =====================================================================

    default void appendLog(LogCollectContext context, LogEntry entry) {
        if (context != null) {
            context.incrementDiscardedCount();
            if (context.getTotalDiscardedCount() != 1) {
                return;
            }
        } else if (!DefaultWarnings.APPEND_NO_CONTEXT_WARNED.compareAndSet(false, true)) {
            return;
        }
        String traceId = context == null ? "unknown" : context.getTraceId();
        System.err.println("[LogCollect-WARN] appendLog not implemented in "
                + getClass().getSimpleName()
                + ", entries are being dropped for traceId=" + traceId
                + ". Implement appendLog or switch to AGGREGATE mode.");
    }

    // =====================================================================
    // 模式2：AGGREGATE
    // =====================================================================

    default void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        throw new UnsupportedOperationException(
                "当前使用 AGGREGATE 模式，须实现 flushAggregatedLog()。"
                        + "如需单条模式，请实现 appendLog() 并设置 collectMode=SINGLE");
    }

    // =====================================================================
    // 格式化
    // =====================================================================

    /**
     * 日志行格式 pattern。
     *
     * <p>默认值：框架在启动时自动从当前项目的 Logback/Log4j2 控制台 Appender 检测实际
     * pattern 并清理（去除颜色、PID 等），检测失败时回退到内置默认格式。
     * 用户覆写此方法可完全自定义格式。
     *
     * <p>支持配置中心动态覆盖：
     * logcollect.global.format.log-line-pattern=...
     *
     * @return 生效中的日志行 pattern
     */
    default String logLinePattern() {
        return LogLineDefaults.getEffectivePattern();
    }

    /**
     * 将单条日志格式化为完整日志行。
     *
     * <p>调用时机：
     * <ul>
     *   <li>AGGREGATE 模式：每条日志进入聚合缓冲区前由框架调用</li>
     *   <li>SINGLE 模式：Handler 可在 appendLog() 中按需手动调用</li>
     * </ul>
     *
     * <p>默认实现基于 {@link #logLinePattern()} 的 pattern 进行格式化。
     *
     * @param entry 单条日志对象
     * @return 格式化后的单行日志文本
     */
    default String formatLogLine(LogEntry entry) {
        return LogLinePatternParser.format(entry, logLinePattern());
    }

    /**
     * 聚合日志块中行与行之间的分隔符。
     *
     * @return 分隔符字符串
     */
    default String aggregatedLogSeparator() {
        return "\n";
    }

    // =====================================================================
    // 过滤
    // =====================================================================

    /**
     * 过滤判断。
     *
     * @param context 当前上下文
     * @param level 日志级别
     * @param messageSummary 消息安全摘要（已做基础清理，最长 256 字符）
     * @return true 表示允许收集
     */
    default boolean shouldCollect(LogCollectContext context, String level, String messageSummary) {
        return true;
    }

    // =====================================================================
    // 模式偏好
    // =====================================================================

    /**
     * 声明处理器期望的收集模式。
     *
     * @return 期望模式，默认 {@link CollectMode#AUTO}
     */
    default CollectMode preferredMode() {
        return CollectMode.AUTO;
    }

    // =====================================================================
    // 降级 / 错误回调
    // =====================================================================

    default void onDegrade(LogCollectContext context, DegradeEvent event) {
    }

    default void onError(LogCollectContext context, Throwable error, String phase) {
    }

    final class DefaultWarnings {
        static final AtomicBoolean APPEND_NO_CONTEXT_WARNED = new AtomicBoolean(false);

        private DefaultWarnings() {
        }
    }
}
