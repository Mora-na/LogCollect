package com.logcollect.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.*;
import com.logcollect.core.buffer.LogCollectBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.pipeline.LogProcessingPipeline;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;

import java.time.LocalDateTime;
import java.util.Map;

public class LogCollectLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
    private static final String MDC_KEY = "_logCollect_traceId";
    private final LogProcessingPipeline pipeline = new LogProcessingPipeline(
            new DefaultLogSanitizer(), new DefaultLogMasker(), true, true, null);

    @Override
    protected void append(ILoggingEvent event) {
        try {
            Map<String, String> mdc = event.getMDCPropertyMap();
            if (mdc == null || !mdc.containsKey(MDC_KEY)) {
                return;
            }
            LogCollectContext ctx = LogCollectContextManager.current();
            if (ctx == null) {
                return;
            }
            if (!isLevelAllowed(event.getLevel(), ctx.getConfig().getLevel())) {
                ctx.incrementDiscardedCount();
                return;
            }
            String rawMessage = event.getFormattedMessage();
            if (rawMessage == null) {
                rawMessage = "";
            }
            LogCollectHandler handler = ctx.getHandler();
            if (handler != null && !handler.shouldCollect(ctx, event.getLevel().toString(), rawMessage)) {
                ctx.incrementDiscardedCount();
                return;
            }
            String processed = pipeline.process(rawMessage);
            LogEntry entry = new LogEntry(
                    ctx.getTraceId(),
                    processed,
                    event.getLevel().toString(),
                    LocalDateTime.now(),
                    event.getThreadName(),
                    event.getLoggerName());
            Object buf = ctx.getBuffer();
            if (buf instanceof LogCollectBuffer) {
                ((LogCollectBuffer) buf).offer(ctx, entry);
            } else {
                handleDirect(ctx, entry);
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.error("Logback appender error", t);
        }
    }

    private boolean isLevelAllowed(Level eventLevel, String minLevel) {
        Level min = Level.toLevel(minLevel, Level.INFO);
        return eventLevel.isGreaterOrEqual(min);
    }

    private void handleDirect(LogCollectContext ctx, LogEntry entry) {
        LogCollectHandler handler = ctx.getHandler();
        if (handler == null) {
            return;
        }
        ctx.incrementCollectedCount();
        ctx.addCollectedBytes(entry.estimateBytes());
        LogCollectCircuitBreaker breaker = getBreaker(ctx);
        if (breaker != null && !breaker.allowWrite()) {
            ctx.incrementDiscardedCount();
            notifyDegrade(ctx, "circuit_open");
            return;
        }
        if (ctx.getCollectMode() == CollectMode.AGGREGATE) {
            String line;
            try {
                line = handler.formatLogLine(entry);
            } catch (Throwable t) {
                notifyError(ctx, t, "formatLogLine");
                line = entry.getContent();
            }
            AggregatedLog agg = new AggregatedLog(
                    line == null ? "" : line,
                    1,
                    (line == null ? 0L : (long) line.length() * 2L),
                    entry.getLevel(),
                    entry.getTime(),
                    entry.getTime(),
                    false);
            try {
                handler.flushAggregatedLog(ctx, agg);
                if (breaker != null) {
                    breaker.recordSuccess();
                }
            } catch (Throwable t) {
                if (breaker != null) {
                    breaker.recordFailure();
                }
                ctx.incrementDiscardedCount();
                notifyError(ctx, t, "flushAggregatedLog");
                notifyDegrade(ctx, "handler_error");
            }
            return;
        }

        try {
            handler.appendLog(ctx, entry);
            if (breaker != null) {
                breaker.recordSuccess();
            }
        } catch (Throwable t) {
            if (breaker != null) {
                breaker.recordFailure();
            }
            ctx.incrementDiscardedCount();
            notifyError(ctx, t, "appendLog");
            notifyDegrade(ctx, "handler_error");
        }
    }

    private LogCollectCircuitBreaker getBreaker(LogCollectContext context) {
        Object breaker = context.getCircuitBreaker();
        return breaker instanceof LogCollectCircuitBreaker ? (LogCollectCircuitBreaker) breaker : null;
    }

    private void notifyDegrade(LogCollectContext context, String reason) {
        LogCollectHandler handler = context.getHandler();
        LogCollectConfig config = context.getConfig();
        if (handler == null || config == null) {
            return;
        }
        try {
            handler.onDegrade(context, new DegradeEvent(
                    context.getTraceId(),
                    context.getMethodSignature(),
                    reason,
                    config.getDegradeStorage(),
                    LocalDateTime.now()));
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("onDegrade callback failed", t);
        }
    }

    private void notifyError(LogCollectContext context, Throwable error, String phase) {
        LogCollectHandler handler = context.getHandler();
        if (handler == null) {
            return;
        }
        try {
            handler.onError(context, error, phase);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("onError callback failed", t);
        }
    }
}
