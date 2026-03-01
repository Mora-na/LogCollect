package com.logcollect.log4j2;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.*;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.core.buffer.AsyncFlushExecutor;
import com.logcollect.core.buffer.LogCollectBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.security.SecurityComponentRegistry;
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

    private static final String MDC_KEY = LogCollectContextManager.TRACE_ID_KEY;

    private volatile SecurityComponentRegistry securityRegistry;
    private volatile Object metrics;

    protected LogCollectLog4j2Appender(String name, Filter filter) {
        super(name, filter, null, true, null);
    }

    @PluginFactory
    public static LogCollectLog4j2Appender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Filter") Filter filter) {
        return new LogCollectLog4j2Appender(name, filter);
    }

    public void setSecurityRegistry(SecurityComponentRegistry securityRegistry) {
        this.securityRegistry = securityRegistry;
    }

    public void setMetrics(Object metrics) {
        this.metrics = metrics;
    }

    @Override
    public void append(LogEvent event) {
        try {
            String traceId = event.getContextData().getValue(MDC_KEY);
            if (traceId == null) {
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
                metricCall(context, "incrementDiscarded", methodKey, "level_filter");
                return;
            }

            String rawMessage = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            LogCollectHandler handler = context.getHandler();
            if (handler != null && !handler.shouldCollect(context, level, rawMessage)) {
                context.incrementDiscardedCount();
                metricCall(context, "incrementDiscarded", methodKey, "handler_filter");
                return;
            }

            Object securityTimer = metricCallWithResult(context, "startSecurityTimer");
            String processed = securityProcess(config, methodKey, rawMessage, context);
            metricCall(context, "stopSecurityTimer", securityTimer, methodKey);

            LogEntry entry = new LogEntry(
                    context.getTraceId(),
                    processed,
                    level,
                    LocalDateTime.now(),
                    event.getThreadName(),
                    event.getLoggerName());

            metricCall(context, "incrementCollected", methodKey, level, context.getCollectMode().name());

            Object buf = context.getBuffer();
            if (config.isUseBuffer() && buf instanceof LogCollectBuffer) {
                boolean offered = ((LogCollectBuffer) buf).offer(context, entry);
                if (!offered) {
                    metricCall(context, "incrementDiscarded", methodKey, "buffer_full");
                }
            } else {
                handleDirect(context, entry, methodKey);
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.error("Log4j2 appender error", t);
        }
    }

    private String securityProcess(LogCollectConfig config, String methodKey, String rawMessage, LogCollectContext context) {
        String content = rawMessage;

        LogSanitizer sanitizer = resolveSanitizer(config);
        String sanitized = sanitizer == null ? content : sanitizer.sanitize(content);
        if (sanitized != null && !sanitized.equals(content)) {
            metricCall(context, "incrementSanitizeHits", methodKey);
        }
        content = sanitized == null ? "" : sanitized;

        LogMasker masker = resolveMasker(config);
        String masked = masker == null ? content : masker.mask(content);
        if (masked != null && !masked.equals(content)) {
            metricCall(context, "incrementMaskHits", methodKey);
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
        return eventLevel != null && eventLevel.isMoreSpecificThan(min);
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
                    java.util.Collections.singletonList(entry.getContent()),
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
                            line == null ? 0L : (long) line.length() * 2L,
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
                    java.util.Collections.singletonList(entry.getContent()),
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
}
