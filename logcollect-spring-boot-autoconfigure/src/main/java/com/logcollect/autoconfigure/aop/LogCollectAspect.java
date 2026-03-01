package com.logcollect.autoconfigure.aop;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.autoconfigure.jdbc.TransactionalLogCollectHandlerWrapper;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.core.buffer.AggregateModeBuffer;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.buffer.LogCollectBuffer;
import com.logcollect.core.buffer.SingleModeBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.degrade.DegradeFileManager;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.mdc.MDCAdapter;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import com.logcollect.core.util.MethodKeyResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Aspect
@Component
public class LogCollectAspect {

    @Autowired
    private LogCollectConfigResolver configResolver;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private TransactionalLogCollectHandlerWrapper txWrapper;

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private LogCollectGlobalSwitch globalSwitch;

    @Autowired(required = false)
    private GlobalBufferMemoryManager globalBufferManager;

    @Autowired(required = false)
    private LogCollectMetrics metrics;

    @Autowired(required = false)
    private DegradeFileManager degradeFileManager;

    private static volatile GlobalBufferMemoryManager fallbackGlobalBufferManager;

    private final ConcurrentHashMap<String, LogCollectCircuitBreaker> breakerCache =
            new ConcurrentHashMap<String, LogCollectCircuitBreaker>();

    private final ConcurrentHashMap<String, AtomicReference<LogCollectConfig>> breakerConfigRefs =
            new ConcurrentHashMap<String, AtomicReference<LogCollectConfig>>();

    @Around("@annotation(logCollect)")
    public Object around(ProceedingJoinPoint pjp, LogCollect logCollect) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        if (globalSwitch != null && !globalSwitch.isEnabled()) {
            return pjp.proceed();
        }
        LogCollectConfig config;
        try {
            config = configResolver.resolve(method, logCollect);
        } catch (Throwable t) {
            LogCollectInternalLogger.error("Config resolve failed, skip log collect", t);
            return pjp.proceed();
        }
        if (!config.isEnabled()) {
            return pjp.proceed();
        }
        String methodKey = MethodKeyResolver.toDisplayKey(method);
        LogCollectCircuitBreaker breaker = getOrCreateBreaker(methodKey, config);

        String traceId = UUID.randomUUID().toString();
        LogCollectHandler handler = resolveHandler(logCollect, config);
        CollectMode collectMode = resolveCollectMode(config, handler);
        config.setEffectiveCollectMode(collectMode);
        LogCollectContext ctx = null;
        try {
            ctx = buildContext(traceId, method, pjp.getArgs(), config, handler, breaker, collectMode);
            if (metrics != null && config.isEnableMetrics()) {
                metrics.recordActiveCollectionStart();
                metrics.registerCircuitBreakerGauge(methodKey, breaker);
            }
            if (LogCollectContextManager.depth() >= config.getMaxNestingDepth()) {
                LogCollectInternalLogger.warn("Max nesting depth {} reached, skip", config.getMaxNestingDepth());
                return pjp.proceed();
            }
            LogCollectContextManager.push(ctx);
            final LogCollectContext finalCtx = ctx;
            final LogCollectHandler finalHandler = handler;
            long beforeStart = System.currentTimeMillis();
            safeInvoke(new Callable<Object>() {
                @Override
                public Object call() {
                    finalHandler.before(finalCtx);
                    return null;
                }
            }, config.getHandlerTimeoutMs(), config.isTransactionIsolation(), finalCtx, "before");
            if (metrics != null && config.isEnableMetrics()) {
                metrics.recordHandlerDuration(methodKey, "before", System.currentTimeMillis() - beforeStart);
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.error("LogCollect before phase error", t);
            notifyHandlerError(ctx, t, "before");
            if (ctx != null) {
                LogCollectContextManager.pop();
            }
            return pjp.proceed();
        }

        Throwable bizError = null;
        Object result = null;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            bizError = t;
            if (ctx != null) {
                ctx.setError(t);
            }
        }
        if (ctx != null && bizError == null) {
            ctx.setReturnValue(result);
        }

        try {
            if (ctx != null) {
                if (config.isTransactionIsolation() && txWrapper != null) {
                    final LogCollectContext flushCtx = ctx;
                    txWrapper.executeInNewTransaction(() -> {
                        closeBuffer(flushCtx);
                        return null;
                    });
                } else {
                    closeBuffer(ctx);
                }
            }
            final LogCollectContext finalCtx = ctx;
            final LogCollectHandler finalHandler = handler;
            long afterStart = System.currentTimeMillis();
            safeInvoke(new Callable<Object>() {
                @Override
                public Object call() {
                    finalHandler.after(finalCtx);
                    return null;
                }
            }, config.getHandlerTimeoutMs(), config.isTransactionIsolation(), finalCtx, "after");
            if (metrics != null && config.isEnableMetrics()) {
                metrics.recordHandlerDuration(methodKey, "after", System.currentTimeMillis() - afterStart);
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.error("LogCollect after phase error", t);
            notifyHandlerError(ctx, t, "after");
        } finally {
            try {
                LogCollectContextManager.pop();
            } catch (Throwable t) {
                LogCollectInternalLogger.error("Context cleanup error", t);
                MDCAdapter.remove("_logCollect_traceId");
            } finally {
                if (metrics != null && config.isEnableMetrics()) {
                    metrics.recordActiveCollectionEnd();
                }
            }
        }

        if (bizError != null) {
            throw bizError;
        }
        return result;
    }

    private LogCollectContext buildContext(String traceId,
                                           Method method,
                                           Object[] args,
                                           LogCollectConfig config,
                                           LogCollectHandler handler,
                                           LogCollectCircuitBreaker breaker,
                                           CollectMode collectMode) {
        GlobalBufferMemoryManager manager = getGlobalBufferManager(config);
        LogCollectBuffer buffer = null;
        if (config.isUseBuffer()) {
            if (collectMode == CollectMode.AGGREGATE) {
                buffer = new AggregateModeBuffer(config.getMaxBufferSize(), config.getMaxBufferBytes(), manager, handler);
            } else {
                buffer = new SingleModeBuffer(config.getMaxBufferSize(), config.getMaxBufferBytes(), manager);
            }
        }
        LogCollectContext context = new LogCollectContext(traceId, method, args, config, handler, buffer, breaker, collectMode);
        if (degradeFileManager != null) {
            context.setAttribute("__degradeFileManager", degradeFileManager);
        }
        if (metrics != null && config.isEnableMetrics()) {
            context.setAttribute("__metrics", metrics);
        }
        if (txWrapper != null && config.isTransactionIsolation()) {
            context.setAttribute("__txWrapper", txWrapper);
        }
        return context;
    }

    private GlobalBufferMemoryManager getGlobalBufferManager(LogCollectConfig config) {
        if (globalBufferManager != null) {
            return globalBufferManager;
        }
        if (fallbackGlobalBufferManager == null) {
            synchronized (LogCollectAspect.class) {
                if (fallbackGlobalBufferManager == null) {
                    fallbackGlobalBufferManager = new GlobalBufferMemoryManager(config.getGlobalBufferTotalMaxBytes());
                }
            }
        }
        return fallbackGlobalBufferManager;
    }

    private LogCollectHandler resolveHandler(LogCollect logCollect, LogCollectConfig config) {
        Class<? extends LogCollectHandler> handlerClass = logCollect.handler();
        if ((handlerClass == null || handlerClass == LogCollectHandler.class)
                && config != null
                && config.getHandlerClass() != null
                && config.getHandlerClass() != LogCollectHandler.class) {
            handlerClass = config.getHandlerClass();
        }
        if (handlerClass == null || handlerClass == LogCollectHandler.class) {
            try {
                return applicationContext.getBean(LogCollectHandler.class);
            } catch (Throwable t) {
                LogCollectContext parent = LogCollectContextManager.current();
                if (parent != null && parent.getHandler() != null) {
                    LogCollectInternalLogger.warn("Default handler resolve failed, fallback to parent handler: {}",
                            parent.getHandler().getClass().getName());
                    return parent.getHandler();
                }
                LogCollectInternalLogger.warn("No default LogCollectHandler bean available, fallback to Noop", t);
                return new NoopLogCollectHandler();
            }
        }
        try {
            return applicationContext.getBean(handlerClass);
        } catch (Throwable t) {
            try {
                LogCollectInternalLogger.warn("Handler bean {} not found, fallback to reflective newInstance",
                        handlerClass.getName(), t);
                return handlerClass.newInstance();
            } catch (Exception e) {
                LogCollectInternalLogger.warn("Handler {} instantiate failed, fallback to Noop",
                        handlerClass.getName(), e);
                return new NoopLogCollectHandler();
            }
        }
    }

    private void closeBuffer(LogCollectContext ctx) {
        try {
            Object buf = ctx.getBuffer();
            if (buf instanceof LogCollectBuffer) {
                ((LogCollectBuffer) buf).closeAndFlush(ctx);
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Log buffer flush failed", t);
        }
    }

    private <T> T safeInvoke(Callable<T> callable, int timeoutMs, boolean transactionIsolation,
                             LogCollectContext context, String phase) {
        try {
            if (transactionIsolation && txWrapper != null) {
                return txWrapper.executeInNewTransaction(callable);
            }
            if (timeoutMs <= 0) {
                return callable.call();
            }
            return executeWithTimeout(callable, timeoutMs);
        } catch (TimeoutException e) {
            LogCollectInternalLogger.warn("Handler execution timeout ({}ms)", timeoutMs);
            notifyHandlerError(context, e, phase);
            if (metrics != null && context != null && context.getConfig() != null && context.getConfig().isEnableMetrics()) {
                metrics.incrementHandlerTimeout(context.getMethodSignature());
            }
            return null;
        } catch (Throwable t) {
            LogCollectInternalLogger.error("Handler execution error", t);
            notifyHandlerError(context, t, phase);
            return null;
        }
    }

    private <T> T executeWithTimeout(Callable<T> callable, int timeoutMs) throws Exception {
        FutureTask<T> task = new FutureTask<T>(callable);
        Thread t = new Thread(task, "logcollect-handler-timeout");
        t.setDaemon(true);
        t.start();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            t.interrupt();
            throw e;
        }
    }

    private LogCollectCircuitBreaker getOrCreateBreaker(String methodKey, LogCollectConfig config) {
        AtomicReference<LogCollectConfig> configRef =
                breakerConfigRefs.computeIfAbsent(methodKey, k -> new AtomicReference<LogCollectConfig>());
        configRef.set(config);
        LogCollectCircuitBreaker breaker =
                breakerCache.computeIfAbsent(methodKey, k -> new LogCollectCircuitBreaker(configRef::get));
        if (circuitBreakerRegistry != null) {
            circuitBreakerRegistry.register(methodKey, breaker, metrics);
        }
        return breaker;
    }

    private void notifyHandlerError(LogCollectContext context, Throwable error, String phase) {
        if (context == null) {
            return;
        }
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

    private CollectMode resolveCollectMode(LogCollectConfig config, LogCollectHandler handler) {
        CollectMode configMode = config == null ? CollectMode.AUTO : config.getCollectMode();
        if (configMode != null && configMode != CollectMode.AUTO) {
            return configMode;
        }
        CollectMode preferred = handler == null ? CollectMode.AUTO : handler.preferredMode();
        if (preferred != null && preferred != CollectMode.AUTO) {
            return preferred;
        }
        return CollectMode.AGGREGATE;
    }

    static class NoopLogCollectHandler implements LogCollectHandler {
        @Override
        public void appendLog(LogCollectContext context, com.logcollect.api.model.LogEntry entry) {}
        @Override
        public void flushAggregatedLog(LogCollectContext context, com.logcollect.api.model.AggregatedLog aggregatedLog) {}
    }
}
