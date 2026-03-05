package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogEntry;

import java.util.Collections;
import java.util.Map;

/**
 * 消费者线程独占复用的处理后记录。
 */
public final class MutableProcessedLogRecord {

    String processedMessage;
    String processedThrowable;
    String level;
    String loggerName;
    String threadName;
    long timestamp;
    String traceId;
    Map<String, String> mdcContext;
    boolean fastPathHit;

    void processFrom(MutableRawLogRecord raw,
                     SecurityPipeline pipeline,
                     SecurityPipeline.SecurityMetrics securityMetrics,
                     long deadlineNanos) {
        pipeline.processRawInto(
                raw.traceId,
                raw.formattedMessage,
                raw.level,
                raw.timestamp,
                raw.threadName,
                raw.loggerName,
                raw.throwableString,
                raw.mdcContext,
                securityMetrics,
                deadlineNanos,
                this);
    }

    LogEntry toLogEntry() {
        return LogEntry.builder()
                .traceId(traceId)
                .content(processedMessage)
                .level(level)
                .timestamp(timestamp)
                .threadName(threadName)
                .loggerName(loggerName)
                .throwableString(processedThrowable)
                .mdcContext(mdcContext == null ? Collections.emptyMap() : mdcContext)
                .build();
    }

    long estimateBytes() {
        return toLogEntry().estimateBytes();
    }

    public String getProcessedMessage() {
        return processedMessage;
    }

    public String getProcessedThrowable() {
        return processedThrowable;
    }

    public String getLevel() {
        return level;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getThreadName() {
        return threadName;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getTraceId() {
        return traceId;
    }

    public Map<String, String> getMdcContext() {
        return mdcContext;
    }

    public boolean isFastPathHit() {
        return fastPathHit;
    }
}
