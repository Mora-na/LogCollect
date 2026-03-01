package com.logcollect.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.*;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.core.buffer.AsyncFlushExecutor;
import com.logcollect.core.buffer.LogCollectBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.security.SecurityComponentRegistry;

import java.time.LocalDateTime;
import java.util.Map;

public class LogCollectLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String MDC_KEY = LogCollectContextManager.TRACE_ID_KEY;

    private volatile SecurityComponentRegistry securityRegistry;
    private volatile Object metrics;

    public void setSecurityRegistry(SecurityComponentRegistry securityRegistry) {
        this.securityRegistry = securityRegistry;
    }

    public void setMetrics(Object metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            Map<String, String> mdc = event.getMDCPropertyMap();
            if (mdc == null || !mdc.containsKey(MDC_KEY)) {
                return;
            }

            LogCollectContext context = LogCollectContextManager.current();
            if (context == null) {
                return;
            }

            LogCollectConfig config = context.getConfig();
            String methodKey = context.getMethodSignature();
            String level = event.getLevel() == null ? "INFO" : event.getLevel().toString();

            if (!isLevelAllowed(event.getLevel(), config.getLevel())) {
                context.incrementDiscardedCount();
                metricCall("incrementDiscarded", methodKey, "level_filter");
                return;
            }

            String rawMessage = event.getFormattedMessage();
            if (rawMessage == null) {
                rawMessage = "";
            }

            LogCollectHandler handler = context.getHandler();
            if (handler != null && !handler.shouldCollect(context, level, rawMessage)) {
                context.incrementDiscardedCount();
                metricCall("incrementDiscarded", methodKey, "handler_filter");
                return;
            }

            Object securityTimer = metricCallWithResult("startSecurityTimer");
            String processed = securityProcess(config, methodKey, rawMessage);
            metricCall("stopSecurityTimer", securityTimer, methodKey);

            LogEntry entry = new LogEntry(
                    context.getTraceId(),
                    processed,
                    level,
                    LocalDateTime.now(),
                    event.getThreadName(),
                    event.getLoggerName());

            metricCall("incrementCollected", methodKey, level, context.getCollectMode().name());

            Object buf = context.getBuffer();
            if (config.isUseBuffer() && buf instanceof LogCollectBuffer) {
                boolean offered = ((LogCollectBuffer) buf).offer(context, entry);
                if (!offered) {
                    metricCall("incrementDiscarded", methodKey, "buffer_full");
                }
            } else {
                handleDirect(context, entry, methodKey);
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.error("Logback appender error", t);
        }
    }

    private String securityProcess(LogCollectConfig config, String methodKey, String rawMessage) {
        String content = rawMessage;

        LogSanitizer sanitizer = resolveSanitizer(config);
        String sanitized = sanitizer == null ? content : sanitizer.sanitize(content);
        if (sanitized != null && !sanitized.equals(content)) {
            metricCall("incrementSanitizeHits", methodKey);
        }
        content = sanitized == null ? "" : sanitized;

        LogMasker masker = resolveMasker(config);
        String masked = masker == null ? content : masker.mask(content);
        if (masked != null && !masked.equals(content)) {
            metricCall("incrementMaskHits", methodKey);
        }
        return masked == null ? "" : masked;
    }

    private LogSanitizer resolveSanitizer(LogCollectConfig config) {
        if (securityRegistry != null) {
            return securityRegistry.getSanitizer(config);
        }
        if (!config.isEnableSanitize()) {
            return null;
        }
        return new DefaultLogSanitizer();
    }

    private LogMasker resolveMasker(LogCollectConfig config) {
        if (securityRegistry != null) {
            return securityRegistry.getMasker(config);
        }
        if (!config.isEnableMask()) {
            return null;
        }
        return new DefaultLogMasker();
    }

    private boolean isLevelAllowed(Level eventLevel, String minLevel) {
        Level min = Level.toLevel(minLevel, Level.INFO);
        return eventLevel != null && eventLevel.isGreaterOrEqual(min);
    }

    private void handleDirect(LogCollectContext context, LogEntry entry, String methodKey) {
        Runnable writeTask = () -> doHandleDirect(context, entry, methodKey);
        if (context.getConfig().isAsync()) {
            AsyncFlushExecutor.submitOrRun(writeTask);
        } else {
            writeTask.run();
        }
    }

    private void doHandleDirect(LogCollectContext context, LogEntry entry, String methodKey) {
        LogCollectHandler handler = context.getHandler();
        if (handler == null) {
            return;
        }
        context.incrementCollectedCount();
        context.addCollectedBytes(entry.estimateBytes());
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            context.incrementDiscardedCount();
            notifyDegrade(context, "circuit_open");
            metricCall("incrementDiscarded", methodKey, "circuit_open");
            return;
        }

        Object persistTimer = metricCallWithResult("startPersistTimer");
        try {
            if (context.getCollectMode() == CollectMode.AGGREGATE) {
                String line;
                try {
                    line = handler.formatLogLine(entry);
                } catch (Throwable t) {
                    notifyError(context, t, "formatLogLine");
                    line = entry.getContent();
                }
                AggregatedLog agg = new AggregatedLog(
                        line == null ? "" : line,
                        1,
                        line == null ? 0L : (long) line.length() * 2L,
                        entry.getLevel(),
                        entry.getTime(),
                        entry.getTime(),
                        false);
                handler.flushAggregatedLog(context, agg);
                metricCall("incrementPersisted", methodKey, CollectMode.AGGREGATE.name());
            } else {
                handler.appendLog(context, entry);
                metricCall("incrementPersisted", methodKey, CollectMode.SINGLE.name());
            }
            if (breaker != null) {
                breaker.recordSuccess();
            }
        } catch (Throwable t) {
            if (breaker != null) {
                breaker.recordFailure();
            }
            context.incrementDiscardedCount();
            notifyError(context, t, "append");
            notifyDegrade(context, "handler_error");
            metricCall("incrementPersistFailed", methodKey);
            metricCall("incrementDegradeTriggered", "persist_failed", methodKey);
        } finally {
            metricCall("stopPersistTimer", persistTimer, methodKey, context.getCollectMode().name());
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

    private void metricCall(String methodName, Object... args) {
        Object target = metrics;
        if (target == null) {
            return;
        }
        invokeReflective(target, methodName, args);
    }

    private Object metricCallWithResult(String methodName, Object... args) {
        Object target = metrics;
        if (target == null) {
            return null;
        }
        return invokeReflective(target, methodName, args);
    }

    private Object invokeReflective(Object target, String methodName, Object... args) {
        try {
            MethodLoop:
            for (java.lang.reflect.Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != args.length) {
                    continue;
                }
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (args[i] == null) {
                        continue;
                    }
                    if (!wrap(parameterTypes[i]).isAssignableFrom(wrap(args[i].getClass()))) {
                        continue MethodLoop;
                    }
                }
                method.setAccessible(true);
                return method.invoke(target, args);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }
}
