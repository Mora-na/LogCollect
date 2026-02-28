package com.logcollect.log4j2;

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
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

import java.time.LocalDateTime;

@Plugin(name = "LogCollect", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class LogCollectLog4j2Appender extends AbstractAppender {
    private static final String MDC_KEY = "_logCollect_traceId";
    private final LogProcessingPipeline pipeline = new LogProcessingPipeline(
            new DefaultLogSanitizer(), new DefaultLogMasker(), true, true, null);

    protected LogCollectLog4j2Appender(String name, Filter filter) {
        super(name, filter, null, true, null);
    }

    @PluginFactory
    public static LogCollectLog4j2Appender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Filter") Filter filter) {
        return new LogCollectLog4j2Appender(name, filter);
    }

    @Override
    public void append(LogEvent event) {
        try {
            String traceId = event.getContextData().getValue(MDC_KEY);
            if (traceId == null) {
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
            String rawMessage = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
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
            LogCollectInternalLogger.error("Log4j2 appender error", t);
        }
    }

    private boolean isLevelAllowed(Level eventLevel, String minLevel) {
        Level min = Level.toLevel(minLevel, Level.INFO);
        return eventLevel.isMoreSpecificThan(min);
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
