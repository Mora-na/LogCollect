package com.logcollect.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.exception.LogCollectDegradeException;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.*;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.core.buffer.AsyncFlushExecutor;
import com.logcollect.core.buffer.LogCollectBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.diagnostics.LogCollectDiag;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.pipeline.SecurityPipeline;
import com.logcollect.core.security.*;

import java.time.LocalDateTime;
import java.util.Collections;
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
        LogCollectContext context = null;
        try {
            Map<String, String> mdc = event.getMDCPropertyMap();
            if (mdc == null || !mdc.containsKey(MDC_KEY)) {
                return;
            }

            context = LogCollectContextManager.current();
            if (context == null) {
                return;
            }

            LogCollectConfig config = context.getConfig();
            String methodKey = context.getMethodSignature();
            String loggerName = event.getLoggerName();
            String level = event.getLevel() == null ? "INFO" : event.getLevel().toString();
            LogCollectDiag.debug("Appender received event: logger=%s level=%s", loggerName, level);

            if (!isLevelAllowed(event.getLevel(), context.getMinLevelInt())) {
                context.incrementDiscardedCount();
                metricCall(context, "incrementDiscarded", methodKey, "level_filter");
                return;
            }
            if (context.isLoggerExcluded(loggerName)) {
                context.incrementDiscardedCount();
                metricCall(context, "incrementDiscarded", methodKey, "logger_filter");
                return;
            }

            String rawMessage = event.getFormattedMessage();
            if (rawMessage == null) {
                rawMessage = "";
            }

            LogCollectHandler handler = context.getHandler();
            String messageSummary = QuickSanitizer.summarize(rawMessage, 256);
            if (handler != null && !handler.shouldCollect(context, level, messageSummary)) {
                context.incrementDiscardedCount();
                metricCall(context, "incrementDiscarded", methodKey, "handler_filter");
                return;
            }

            long eventTimestamp = event.getTimeStamp();
            String content = StringLengthGuard.guardContent(rawMessage, config.getGuardMaxContentLength());
            String throwable = StringLengthGuard.guardThrowable(
                    extractThrowableString(event), config.getGuardMaxThrowableLength());

            LogEntry rawEntry = LogEntry.builder()
                    .traceId(context.getTraceId())
                    .content(content)
                    .level(level)
                    .timestamp(eventTimestamp)
                    .threadName(event.getThreadName())
                    .loggerName(loggerName)
                    .throwableString(throwable)
                    .mdcContext(mdc)
                    .build();

            Object securityTimer = metricCallWithResult(context, "startSecurityTimer");
            LogEntry entry = securityProcess(config, methodKey, rawEntry, context);
            metricCall(context, "stopSecurityTimer", securityTimer, methodKey);

            metricCall(context, "incrementCollected", methodKey, level, context.getCollectMode().name());

            Object buf = context.getBuffer();
            if (config.isUseBuffer() && buf instanceof LogCollectBuffer) {
                ((LogCollectBuffer) buf).offer(context, entry);
            } else {
                handleDirect(context, entry, methodKey);
            }
        } catch (Throwable t) {
            rethrowDegradeIfNecessary(context, t);
            addError("LogCollect appender error", t);
            LogCollectInternalLogger.error("Logback appender error", t);
        }
    }

    private LogEntry securityProcess(LogCollectConfig config,
                                     String methodKey,
                                     LogEntry rawEntry,
                                     LogCollectContext context) {
        LogSanitizer sanitizer = resolveSanitizer(config);
        LogMasker masker = resolveMasker(config);

        SecurityPipeline pipeline = new SecurityPipeline(sanitizer, masker);
        return pipeline.process(rawEntry, new SecurityPipeline.SecurityMetrics() {
            @Override
            public void onContentSanitized() {
                metricCall(context, "incrementSanitizeHits", methodKey);
            }

            @Override
            public void onThrowableSanitized() {
                metricCall(context, "incrementSanitizeHits", methodKey);
            }

            @Override
            public void onContentMasked() {
                metricCall(context, "incrementMaskHits", methodKey);
            }

            @Override
            public void onThrowableMasked() {
                metricCall(context, "incrementMaskHits", methodKey);
            }
        });
    }

    private String extractThrowableString(ILoggingEvent event) {
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy == null) {
            return null;
        }
        return ThrowableProxyUtil.asString(throwableProxy);
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

    private boolean isLevelAllowed(Level eventLevel, int minLevelInt) {
        return eventLevel != null && toLevelRank(eventLevel.toString()) >= minLevelInt;
    }

    private int toLevelRank(String level) {
        if (level == null) {
            return 0;
        }
        String v = level.toUpperCase();
        if ("FATAL".equals(v)) return 5;
        if ("ERROR".equals(v)) return 4;
        if ("WARN".equals(v)) return 3;
        if ("INFO".equals(v)) return 2;
        if ("DEBUG".equals(v)) return 1;
        if ("TRACE".equals(v)) return 0;
        return 0;
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
            metricCall(context, "incrementDiscarded", methodKey, "circuit_open");
            DegradeFallbackHandler.handleDegraded(
                    context, "circuit_open",
                    Collections.singletonList(entry.getContent()),
                    entry.getLevel());
            return;
        }

        Object persistTimer = metricCallWithResult(context, "startPersistTimer");
        try {
            executeWithTx(context, () -> {
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
                            estimateStringBytes(line),
                            entry.getLevel(),
                            entry.getTime(),
                            entry.getTime(),
                            false);
                    handler.flushAggregatedLog(context, agg);
                    metricCall(context, "incrementPersisted", methodKey, CollectMode.AGGREGATE.name());
                } else {
                    handler.appendLog(context, entry);
                    metricCall(context, "incrementPersisted", methodKey, CollectMode.SINGLE.name());
                }
            });
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
            metricCall(context, "incrementPersistFailed", methodKey);
            metricCall(context, "incrementDegradeTriggered", "persist_failed", methodKey);
            DegradeFallbackHandler.handleDegraded(
                    context, "persist_failed",
                    Collections.singletonList(entry.getContent()),
                    entry.getLevel());
        } finally {
            metricCall(context, "stopPersistTimer", persistTimer, methodKey, context.getCollectMode().name());
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

    private long estimateStringBytes(String value) {
        if (value == null) {
            return 0L;
        }
        return 48L + ((long) value.length() << 1);
    }

    private void metricCall(LogCollectContext context, String methodName, Object... args) {
        if (context != null && context.getConfig() != null && !context.getConfig().isEnableMetrics()) {
            return;
        }
        Object target = metrics;
        if (target == null) {
            return;
        }
        invokeReflective(target, methodName, args);
    }

    private Object metricCallWithResult(LogCollectContext context, String methodName, Object... args) {
        if (context != null && context.getConfig() != null && !context.getConfig().isEnableMetrics()) {
            return null;
        }
        Object target = metrics;
        if (target == null) {
            return null;
        }
        return invokeReflective(target, methodName, args);
    }

    private void executeWithTx(LogCollectContext context, Runnable action) {
        if (context == null || context.getConfig() == null || !context.getConfig().isTransactionIsolation()) {
            action.run();
            return;
        }
        Object txWrapper = context.getAttribute("__txWrapper");
        if (txWrapper == null) {
            action.run();
            return;
        }
        if (!invokeIfPresent(txWrapper, "executeInNewTransaction", action)) {
            action.run();
        }
    }

    private boolean invokeIfPresent(Object target, String methodName, Object... args) {
        try {
            MethodLoop:
            for (java.lang.reflect.Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterTypes().length != args.length) {
                    continue;
                }
                Class<?>[] paramTypes = method.getParameterTypes();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (args[i] == null) {
                        continue;
                    }
                    if (!wrap(paramTypes[i]).isAssignableFrom(wrap(args[i].getClass()))) {
                        continue MethodLoop;
                    }
                }
                method.invoke(target, args);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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

    private void rethrowDegradeIfNecessary(LogCollectContext context, Throwable t) {
        if (!(t instanceof LogCollectDegradeException) || context == null || context.getConfig() == null) {
            return;
        }
        if (!context.getConfig().isBlockWhenDegradeFail()) {
            return;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new RuntimeException(t);
    }
}
