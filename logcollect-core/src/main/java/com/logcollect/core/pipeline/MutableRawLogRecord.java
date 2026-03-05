package com.logcollect.core.pipeline;

import java.util.Map;

/**
 * 预分配环形缓冲区槽位对象，避免生产者逐事件分配。
 */
public final class MutableRawLogRecord {

    String formattedMessage;
    String level;
    String loggerName;
    String threadName;
    long timestamp;
    String traceId;
    String throwableString;
    Map<String, String> mdcContext;

    public void populate(String formattedMessage,
                         String level,
                         String loggerName,
                         String threadName,
                         long timestamp,
                         String traceId,
                         String throwableString,
                         Map<String, String> mdcContext) {
        this.formattedMessage = formattedMessage;
        this.level = level;
        this.loggerName = loggerName;
        this.threadName = threadName;
        this.timestamp = timestamp;
        this.traceId = traceId;
        this.throwableString = throwableString;
        this.mdcContext = mdcContext;
    }

    void clearReferences() {
        this.formattedMessage = null;
        this.level = null;
        this.loggerName = null;
        this.threadName = null;
        this.traceId = null;
        this.throwableString = null;
        this.mdcContext = null;
    }
}
