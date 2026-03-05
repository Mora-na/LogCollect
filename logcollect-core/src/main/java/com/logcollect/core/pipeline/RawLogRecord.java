package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogCollectContext;

import java.util.Map;

/**
 * 业务线程在 Appender 热路径提取的原始日志字段。
 */
public final class RawLogRecord {

    public final String content;
    public final String throwableString;
    public final String level;
    public final String loggerName;
    public final String threadName;
    public final long timestamp;
    public final Map<String, String> mdcCopy;
    public final LogCollectContext context;

    public RawLogRecord(String content,
                        String throwableString,
                        String level,
                        String loggerName,
                        String threadName,
                        long timestamp,
                        Map<String, String> mdcCopy,
                        LogCollectContext context) {
        this.content = content;
        this.throwableString = throwableString;
        this.level = level;
        this.loggerName = loggerName;
        this.threadName = threadName;
        this.timestamp = timestamp;
        this.mdcCopy = mdcCopy;
        this.context = context;
    }
}
