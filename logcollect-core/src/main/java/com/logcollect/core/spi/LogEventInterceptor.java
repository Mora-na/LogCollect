package com.logcollect.core.spi;

import com.logcollect.api.model.LogEntry;

/**
 * 日志条目拦截扩展点。
 *
 * <p>用于在日志真正写入缓冲区前做最后一次拦截加工。
 * 返回 null 表示丢弃该条日志，返回新对象可替换原日志内容。
 */
public interface LogEventInterceptor {
    /**
     * 在日志条目入缓冲前执行拦截。
     *
     * @param entry 当前日志条目
     * @return 处理后的日志条目；返回 null 表示跳过该条
     */
    LogEntry beforeAppend(LogEntry entry);
}
