package com.logcollect.autoconfigure.aop;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.backpressure.BackpressureCallback;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.exception.LogCollectDegradeException;
import com.logcollect.api.exception.LogCollectException;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.autoconfigure.circuitbreaker.CircuitBreakerRegistry;
import com.logcollect.autoconfigure.jdbc.TransactionalLogCollectHandlerWrapper;
import com.logcollect.autoconfigure.metrics.LogCollectMetrics;
import com.logcollect.autoconfigure.servlet.LogCollectServletAsyncFinalizer;
import com.logcollect.autoconfigure.servlet.LogCollectServletAsyncSupport;
import com.logcollect.core.buffer.*;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.config.LogCollectConfigResolver;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.degrade.DegradeFileManager;
import com.logcollect.core.handler.HandlerTimeoutGuard;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.mdc.MDCAdapter;
import com.logcollect.core.pipeline.LogCollectPipelineManager;
import com.logcollect.core.pipeline.SecurityPipeline;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.security.SecurityComponentRegistry;
import com.logcollect.core.util.MethodKeyResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private SecurityComponentRegistry securityRegistry;

    @Autowired(required = false)
    private DegradeFileManager degradeFileManager;

    @Autowired(required = false)
    private com.logcollect.autoconfigure.LogCollectBufferRegistry bufferRegistry;

    @Autowired(required = false)
    private LogCollectPipelineManager pipelineManager;

    private static final String ATTR_SECURITY_PIPELINE = "__securityPipeline";
    private static final String ATTR_SECURITY_METRICS = "__securityMetrics";

    private static volatile GlobalBufferMemoryManager fallbackGlobalBufferManager;
    private static final NoopLogCollectHandler NOOP_HANDLER = new NoopLogCollectHandler();

    private final ConcurrentHashMap<String, LogCollectCircuitBreaker> breakerCache =
            new ConcurrentHashMap<String, LogCollectCircuitBreaker>();

    private final ConcurrentHashMap<String, AtomicReference<LogCollectConfig>> breakerConfigRefs =
            new ConcurrentHashMap<String, AtomicReference<LogCollectConfig>>();
    private final ConcurrentHashMap<Method, ResolvedHandler> handlerCache =
            new ConcurrentHashMap<Method, ResolvedHandler>();
    private volatile Instant handlerCacheRefreshTime;
    private final ConcurrentHashMap<Class<? extends BackpressureCallback>, BackpressureCallback> backpressureCallbackCache =
            new ConcurrentHashMap<Class<? extends BackpressureCallback>, BackpressureCallback>();
    private final java.util.Set<String> registeredBreakerGauges = ConcurrentHashMap.newKeySet();
    private final AtomicReference<HandlerTimeoutGuard> timeoutGuardRef = new AtomicReference<HandlerTimeoutGuard>();

    @Around("@annotation(logCollect)")
    public Object around(ProceedingJoinPoint pjp, LogCollect logCollect) throws Throwable {
        try {
            Method method = ((MethodSignature) pjp.getSignature()).getMethod();
            if (globalSwitch != null && !globalSwitch.isEnabled()) {
                return pjp.proceed();
            }
            LogCollectConfig config;
            try {
                config = configResolver.resolve(method, logCollect);
            } catch (Exception e) {
                LogCollectInternalLogger.error("Config resolve failed, skip log collect", e);
                frameworkWarn("Failed to resolve @LogCollect config, skip collecting", e);
                return pjp.proceed();
            }
            if (!config.isEnabled()) {
                return pjp.proceed();
            }
            if (LogCollectContextManager.depth() >= config.getMaxNestingDepth()) {
                LogCollectInternalLogger.warn("Max nesting depth {} reached, skip", config.getMaxNestingDepth());
                return pjp.proceed();
            }
            String methodKey = MethodKeyResolver.toDisplayKey(method);
            LogCollectCircuitBreaker breaker = getOrCreateBreaker(methodKey, config);

            String traceId = UUID.randomUUID().toString();
            LogCollectHandler handler = resolveHandler(method, logCollect, config);
            CollectMode collectMode = resolveCollectMode(config, handler);
            config.setEffectiveCollectMode(collectMode);
            LogCollectContext ctx = null;
            try {
                ctx = buildContext(traceId, method, pjp.getArgs(), config, handler, breaker, collectMode, logCollect);
                if (metrics != null && config.isEnableMetrics()) {
                    metrics.recordActiveCollectionStart();
                    if (registeredBreakerGauges.add(methodKey)) {
                        metrics.registerCircuitBreakerGauge(methodKey, breaker);
                    }
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
            } catch (Exception e) {
                forceOpenIfError(breaker, e);
                LogCollectInternalLogger.error("LogCollect before phase error", e);
                frameworkWarn("Failed to start collecting, skip", e);
                notifyHandlerError(ctx, e, "before");
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

            boolean deferredServletAsyncCompletion = false;
            try {
                if (ctx != null && bizError == null) {
                    deferredServletAsyncCompletion = deferServletAsyncFinalizationIfNeeded(ctx, handler);
                }
                if (!deferredServletAsyncCompletion) {
                    finishCollection(ctx, handler, true);
                }
            } finally {
                try {
                    LogCollectContextManager.pop();
                } catch (Exception e) {
                    LogCollectInternalLogger.error("Context cleanup error", e);
                    MDCAdapter.remove("_logCollect_traceId");
                } finally {
                    if (!deferredServletAsyncCompletion && metrics != null && config.isEnableMetrics()) {
                        metrics.recordActiveCollectionEnd();
                    }
                }
            }

            if (bizError != null) {
                throw bizError;
            }
            return result;
        } catch (Error error) {
            LogCollectInternalLogger.error("LogCollect fatal error, forcing disable", error);
            if (globalSwitch != null) {
                globalSwitch.onConfigChange(false);
            }
            throw error;
        }
    }

    private LogCollectContext buildContext(String traceId,
                                           Method method,
                                           Object[] args,
                                           LogCollectConfig config,
                                           LogCollectHandler handler,
                                           LogCollectCircuitBreaker breaker,
                                           CollectMode collectMode,
                                           LogCollect logCollect) {
        GlobalBufferMemoryManager manager = getGlobalBufferManager(config);
        Object buffer = null;
        boolean pipelineActive = config != null
                && config.isUseBuffer()
                && config.isPipelineEnabled()
                && pipelineManager != null;
        if (config.isUseBuffer()) {
            if (pipelineActive) {
                if (collectMode == CollectMode.SINGLE) {
                    buffer = new SingleWriterBuffer(
                            config.getMaxBufferSize(),
                            config.getMaxBufferBytes(),
                            config.getMaxBufferSize());
                }
            } else {
                BoundedBufferPolicy policy = new BoundedBufferPolicy(
                        config.getMaxBufferBytes(),
                        config.getMaxBufferSize(),
                        parseOverflowStrategy(config.getBufferOverflowStrategy()));
                LogCollectBuffer legacyBuffer;
                if (collectMode == CollectMode.AGGREGATE) {
                    legacyBuffer = new AggregateModeBuffer(
                            config.getMaxBufferSize(),
                            config.getMaxBufferBytes(),
                            manager,
                            handler,
                            policy);
                } else {
                    legacyBuffer = new SingleModeBuffer(
                            config.getMaxBufferSize(),
                            config.getMaxBufferBytes(),
                            manager,
                            policy);
                }
                buffer = legacyBuffer;
                if (bufferRegistry != null) {
                    bufferRegistry.register(legacyBuffer);
                }
            }
        }
        LogCollectContext context = new LogCollectContext(traceId, method, args, config, handler, buffer, breaker, collectMode);
        if (degradeFileManager != null) {
            context.setAttribute("__degradeFileManager", degradeFileManager);
        }
        if (manager != null) {
            context.setAttribute("__globalBufferManager", manager);
        }
        com.logcollect.api.metrics.LogCollectMetrics bridgeMetrics = resolveMetricsBridge(config);
        context.setAttribute("__metrics", bridgeMetrics);
        if (metrics != null && config.isEnableMetrics()) {
            metrics.prepareMethodMeters(context.getMethodSignature());
        }
        if (txWrapper != null && config.isTransactionIsolation()) {
            context.setAttribute("__txWrapper", (TransactionExecutor) txWrapper);
        }
        BackpressureCallback backpressureCallback = resolveBackpressureCallback(logCollect, config);
        if (backpressureCallback != null) {
            context.setAttribute("__backpressureCallback", backpressureCallback);
        }
        prepareSecuritySession(context, config, context.getMethodSignature(), bridgeMetrics);
        if (pipelineActive) {
            pipelineManager.registerContext(context);
        }
        return context;
    }

    private void prepareSecuritySession(LogCollectContext context,
                                        LogCollectConfig config,
                                        String methodKey,
                                        com.logcollect.api.metrics.LogCollectMetrics bridgeMetrics) {
        LogSanitizer sanitizer = resolveSanitizer(config);
        LogMasker masker = resolveMasker(config);
        SecurityPipeline pipeline = new SecurityPipeline(sanitizer, masker);
        context.setAttribute(ATTR_SECURITY_PIPELINE, pipeline);
        context.setAttribute(ATTR_SECURITY_METRICS, new SecurityPipeline.SecurityMetrics() {
            @Override
            public void onContentSanitized() {
                bridgeMetrics.incrementSanitizeHits(methodKey);
            }

            @Override
            public void onThrowableSanitized() {
                bridgeMetrics.incrementSanitizeHits(methodKey);
            }

            @Override
            public void onContentMasked() {
                bridgeMetrics.incrementMaskHits(methodKey);
            }

            @Override
            public void onThrowableMasked() {
                bridgeMetrics.incrementMaskHits(methodKey);
            }

            @Override
            public void onFastPathHit() {
                bridgeMetrics.incrementFastPathHits(methodKey);
            }
        });
    }

    private com.logcollect.api.metrics.LogCollectMetrics resolveMetricsBridge(LogCollectConfig config) {
        if (config == null || !config.isEnableMetrics() || metrics == null) {
            return NoopLogCollectMetrics.INSTANCE;
        }
        return metrics;
    }

    private LogSanitizer resolveSanitizer(LogCollectConfig config) {
        if (securityRegistry != null) {
            return securityRegistry.getSanitizer(config);
        }
        if (config == null || !config.isEnableSanitize()) {
            return null;
        }
        return new DefaultLogSanitizer();
    }

    private LogMasker resolveMasker(LogCollectConfig config) {
        if (securityRegistry != null) {
            return securityRegistry.getMasker(config);
        }
        if (config == null || !config.isEnableMask()) {
            return null;
        }
        return new DefaultLogMasker();
    }

    private GlobalBufferMemoryManager getGlobalBufferManager(LogCollectConfig config) {
        if (globalBufferManager != null) {
            return globalBufferManager;
        }
        if (fallbackGlobalBufferManager == null) {
            synchronized (LogCollectAspect.class) {
                if (fallbackGlobalBufferManager == null) {
                    fallbackGlobalBufferManager = new GlobalBufferMemoryManager(
                            config.getGlobalBufferTotalMaxBytes(),
                            GlobalBufferMemoryManager.CounterMode.EXACT_CAS,
                            config.getGlobalBufferHardCeilingBytes());
                }
            }
        }
        return fallbackGlobalBufferManager;
    }

    private LogCollectHandler resolveHandler(Method method, LogCollect logCollect, LogCollectConfig config) {
        refreshHandlerCacheIfNeeded();
        Class<? extends LogCollectHandler> handlerClass = resolveHandlerClass(logCollect, config);
        if (handlerClass == null || handlerClass == LogCollectHandler.class) {
            Map<String, LogCollectHandler> handlers = applicationContext.getBeansOfType(LogCollectHandler.class);
            if (handlers.isEmpty()) {
                return resolveDefaultHandler();
            }
        }
        final Class<? extends LogCollectHandler> targetClass = handlerClass;
        ResolvedHandler resolved = handlerCache.compute(method, (m, existing) -> {
            if (existing != null && existing.matches(targetClass)) {
                return existing;
            }
            LogCollectHandler handler;
            if (targetClass != null && targetClass != LogCollectHandler.class) {
                handler = resolveSpecifiedHandler(targetClass);
            } else {
                handler = resolveDefaultHandler();
            }
            return new ResolvedHandler(targetClass, handler);
        });
        return resolved == null ? resolveDefaultHandler() : resolved.getHandler();
    }

    private void refreshHandlerCacheIfNeeded() {
        Instant refreshTime = configResolver == null ? null : configResolver.getLastRefreshTime();
        if (Objects.equals(refreshTime, handlerCacheRefreshTime)) {
            return;
        }
        handlerCache.clear();
        handlerCacheRefreshTime = refreshTime;
    }

    private Class<? extends LogCollectHandler> resolveHandlerClass(LogCollect logCollect, LogCollectConfig config) {
        Class<? extends LogCollectHandler> handlerClass = logCollect == null ? null : logCollect.handler();
        if ((handlerClass == null || handlerClass == LogCollectHandler.class)
                && config != null
                && config.getHandlerClass() != null
                && config.getHandlerClass() != LogCollectHandler.class) {
            handlerClass = config.getHandlerClass();
        }
        return handlerClass;
    }

    private LogCollectHandler resolveSpecifiedHandler(Class<? extends LogCollectHandler> handlerClass) {
        Map<String, ? extends LogCollectHandler> candidates = applicationContext.getBeansOfType(handlerClass);
        if (candidates.isEmpty()) {
            throw new LogCollectException("Handler " + handlerClass.getName() + " not found");
        }
        if (candidates.size() == 1) {
            return candidates.values().iterator().next();
        }
        LogCollectHandler primaryHandler = resolvePrimaryHandler(candidates);
        if (primaryHandler != null) {
            return primaryHandler;
        }
        throw new LogCollectException("Multiple beans found for handler "
                + handlerClass.getName() + ", please mark one bean with @Primary");
    }

    private LogCollectHandler resolveDefaultHandler() {
        Map<String, LogCollectHandler> handlers = applicationContext.getBeansOfType(LogCollectHandler.class);
        if (handlers.isEmpty()) {
            LogCollectContext parent = LogCollectContextManager.current();
            if (parent != null && parent.getHandler() != null) {
                return parent.getHandler();
            }
            LogCollectInternalLogger.warn("No default LogCollectHandler bean available, fallback to Noop");
            return NOOP_HANDLER;
        }
        if (handlers.size() == 1) {
            return handlers.values().iterator().next();
        }
        LogCollectHandler primaryHandler = resolvePrimaryHandler(handlers);
        if (primaryHandler != null) {
            return primaryHandler;
        }
        throw new LogCollectException(
                "Multiple LogCollectHandler found, specify @LogCollect(handler=...) or mark one bean as @Primary");
    }

    private LogCollectHandler resolvePrimaryHandler(Map<String, ? extends LogCollectHandler> handlers) {
        LogCollectHandler primary = null;
        for (Map.Entry<String, ? extends LogCollectHandler> entry : handlers.entrySet()) {
            Primary annotation = applicationContext.findAnnotationOnBean(entry.getKey(), Primary.class);
            if (annotation == null) {
                continue;
            }
            if (primary != null) {
                throw new LogCollectException("Multiple @Primary LogCollectHandler beans found");
            }
            primary = entry.getValue();
        }
        return primary;
    }

    private BackpressureCallback resolveBackpressureCallback(LogCollect logCollect, LogCollectConfig config) {
        Class<? extends BackpressureCallback> callbackClass =
                logCollect == null ? BackpressureCallback.class : logCollect.backpressure();
        if ((callbackClass == null || callbackClass == BackpressureCallback.class)
                && config != null
                && config.getBackpressureCallbackClass() != null
                && config.getBackpressureCallbackClass() != BackpressureCallback.class) {
            callbackClass = config.getBackpressureCallbackClass();
        }
        if (callbackClass == null || callbackClass == BackpressureCallback.class) {
            return null;
        }
        final Class<? extends BackpressureCallback> target = callbackClass;
        return backpressureCallbackCache.computeIfAbsent(target, this::instantiateBackpressureCallback);
    }

    private BackpressureCallback instantiateBackpressureCallback(Class<? extends BackpressureCallback> callbackClass) {
        Map<String, ? extends BackpressureCallback> candidates = applicationContext.getBeansOfType(callbackClass);
        if (candidates.isEmpty()) {
            try {
                return callbackClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new LogCollectException("BackpressureCallback " + callbackClass.getName() + " cannot be created", e);
            }
        }
        if (candidates.size() == 1) {
            return candidates.values().iterator().next();
        }
        BackpressureCallback primary = resolvePrimaryBackpressureCallback(candidates);
        if (primary != null) {
            return primary;
        }
        throw new LogCollectException("Multiple beans found for BackpressureCallback "
                + callbackClass.getName() + ", please mark one bean with @Primary");
    }

    private BackpressureCallback resolvePrimaryBackpressureCallback(
            Map<String, ? extends BackpressureCallback> callbacks) {
        BackpressureCallback primary = null;
        for (Map.Entry<String, ? extends BackpressureCallback> entry : callbacks.entrySet()) {
            Primary annotation = applicationContext.findAnnotationOnBean(entry.getKey(), Primary.class);
            if (annotation == null) {
                continue;
            }
            if (primary != null) {
                throw new LogCollectException("Multiple @Primary BackpressureCallback beans found");
            }
            primary = entry.getValue();
        }
        return primary;
    }

    private void closeBuffer(LogCollectContext ctx) {
        try {
            if (ctx != null
                    && ctx.getConfig() != null
                    && ctx.getConfig().isPipelineEnabled()
                    && ctx.getPipelineQueue() != null
                    && pipelineManager != null) {
                pipelineManager.closeContext(ctx);
                return;
            }
            Object buf = ctx.getBuffer();
            if (buf instanceof LogCollectBuffer) {
                ((LogCollectBuffer) buf).closeAndFlush(ctx);
                if (bufferRegistry != null) {
                    bufferRegistry.unregister((LogCollectBuffer) buf);
                }
            }
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Log buffer flush failed", e);
            frameworkWarn("Log buffer flush failed", e);
            if (shouldPropagateDegradeException(ctx, e)) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException(e);
            }
        } catch (Error error) {
            LogCollectInternalLogger.error("Log buffer flush failed with fatal error", error);
            throw error;
        }
    }

    private boolean deferServletAsyncFinalizationIfNeeded(LogCollectContext ctx, LogCollectHandler handler) {
        Object request = LogCollectServletAsyncSupport.currentRequest();
        if (request == null || !LogCollectServletAsyncSupport.isAsyncStarted(request)) {
            return false;
        }
        LogCollectServletAsyncSupport.AsyncState asyncState = LogCollectServletAsyncSupport.getAsyncState(request);
        if (asyncState == null) {
            return false;
        }
        LogCollectContextManager.retain(ctx);
        asyncState.registerFinalizer(new ServletAsyncCompletion(ctx, handler));
        return true;
    }

    private void finishCollection(LogCollectContext ctx,
                                  LogCollectHandler handler,
                                  boolean propagateDegradeException) {
        if (ctx == null) {
            return;
        }
        try {
            closeBufferWithIsolation(ctx);
            invokeAfterHandler(ctx, handler);
        } catch (Exception e) {
            forceOpenIfError(resolveBreaker(ctx), e);
            LogCollectInternalLogger.error("LogCollect after phase error", e);
            frameworkWarn("Failed to flush, data may be lost", e);
            notifyHandlerError(ctx, e, "after");
            if (propagateDegradeException && shouldPropagateDegradeException(ctx, e)) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException(e);
            }
        }
    }

    private void closeBufferWithIsolation(LogCollectContext ctx) throws Exception {
        if (ctx == null) {
            return;
        }
        LogCollectConfig config = ctx.getConfig();
        if (config != null && config.isTransactionIsolation() && txWrapper != null) {
            final LogCollectContext flushCtx = ctx;
            txWrapper.executeInNewTransaction(() -> {
                closeBuffer(flushCtx);
                return null;
            });
            return;
        }
        closeBuffer(ctx);
    }

    private void invokeAfterHandler(LogCollectContext ctx, LogCollectHandler handler) {
        if (ctx == null || handler == null) {
            return;
        }
        LogCollectConfig config = ctx.getConfig();
        long afterStart = System.currentTimeMillis();
        final LogCollectContext finalCtx = ctx;
        final LogCollectHandler finalHandler = handler;
        safeInvoke(new Callable<Object>() {
            @Override
            public Object call() {
                finalHandler.after(finalCtx);
                return null;
            }
        }, config == null ? 0 : config.getHandlerTimeoutMs(),
                config != null && config.isTransactionIsolation(),
                finalCtx,
                "after");
        if (metrics != null && config != null && config.isEnableMetrics()) {
            metrics.recordHandlerDuration(ctx.getMethodSignature(), "after", System.currentTimeMillis() - afterStart);
        }
    }

    private LogCollectCircuitBreaker resolveBreaker(LogCollectContext ctx) {
        if (ctx == null) {
            return null;
        }
        Object breaker = ctx.getCircuitBreaker();
        return breaker instanceof LogCollectCircuitBreaker ? (LogCollectCircuitBreaker) breaker : null;
    }

    private <T> T safeInvoke(Callable<T> callable, int timeoutMs, boolean transactionIsolation,
                             LogCollectContext context, String phase) {
        boolean interruptedBefore = Thread.currentThread().isInterrupted();
        AtomicBoolean timeoutHandled = new AtomicBoolean(false);
        long startNanos = System.nanoTime();
        HandlerTimeoutGuard guard = null;
        long guardHandle = 0L;
        long timeoutNanos = timeoutMs <= 0
                ? 0L
                : TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        if (timeoutNanos > 0L) {
            guard = resolveTimeoutGuard(context == null ? null : context.getConfig());
            guardHandle = guard.registerDeadline(
                    Thread.currentThread(),
                    startNanos + timeoutNanos,
                    timeoutNanos,
                    context == null ? "unknown" : context.getMethodSignature(),
                    phase);
        }
        try {
            T result = invokeWithIsolation(callable, transactionIsolation);
            if (guard != null && guard.wasTimedOut(guardHandle)) {
                handleTimeoutOnce(context, phase, timeoutMs, timeoutHandled);
                return null;
            }
            if (timeoutNanos > 0L) {
                long elapsedNanos = System.nanoTime() - startNanos;
                if (elapsedNanos > timeoutNanos) {
                    if (metrics != null && context != null && context.getConfig() != null && context.getConfig().isEnableMetrics()) {
                        metrics.incrementHandlerTimeout(context.getMethodSignature());
                    }
                    LogCollectInternalLogger.warn("Handler {} exceeded timeout {}ms by {}ms",
                            phase,
                            Integer.valueOf(timeoutMs),
                            Long.valueOf(TimeUnit.NANOSECONDS.toMillis(elapsedNanos - timeoutNanos)));
                }
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (guard != null && guard.wasTimedOut(guardHandle)) {
                handleTimeoutOnce(context, phase, timeoutMs, timeoutHandled);
            } else {
                notifyHandlerError(context, e, phase);
            }
            return null;
        } catch (Exception e) {
            forceOpenIfError(context, e);
            if (shouldPropagateDegradeException(context, e)) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException(e);
            }
            LogCollectInternalLogger.error("Handler execution error", e);
            notifyHandlerError(context, e, phase);
            return null;
        } finally {
            if (guard != null) {
                HandlerTimeoutGuard.DeregisterResult result = guard.deregister(guardHandle);
                if (result.isOwner() && result.isTimedOut()) {
                    handleTimeoutOnce(context, phase, timeoutMs, timeoutHandled);
                }
            }
            if (!interruptedBefore) {
                Thread.interrupted();
            } else {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleTimeoutOnce(LogCollectContext context,
                                   String phase,
                                   int timeoutMs,
                                   AtomicBoolean timeoutHandled) {
        if (!timeoutHandled.compareAndSet(false, true)) {
            return;
        }
        notifyHandlerError(context, new TimeoutException("Handler " + phase + " timeout=" + timeoutMs + "ms"), phase);
    }

    private HandlerTimeoutGuard resolveTimeoutGuard(LogCollectConfig config) {
        int watchdogIntervalMs = config == null ? 100 : Math.max(10, config.getHandlerWatchdogIntervalMs());
        int slots = config == null ? 64 : Math.max(8, config.getHandlerWatchdogSlots());
        HandlerTimeoutGuard existing = timeoutGuardRef.get();
        if (existing != null && existing.matches(slots, watchdogIntervalMs)) {
            return existing;
        }
        HandlerTimeoutGuard candidate = new HandlerTimeoutGuard(slots, watchdogIntervalMs, this::onHandlerTimeout);
        while (true) {
            HandlerTimeoutGuard current = timeoutGuardRef.get();
            if (current != null && current.matches(slots, watchdogIntervalMs)) {
                candidate.shutdown();
                return current;
            }
            if (timeoutGuardRef.compareAndSet(current, candidate)) {
                if (current != null) {
                    current.shutdown();
                }
                return candidate;
            }
        }
    }

    private void onHandlerTimeout(HandlerTimeoutGuard.TimeoutEvent event) {
        if (event == null) {
            return;
        }
        if (metrics != null && event.getMethodKey() != null) {
            metrics.incrementHandlerTimeout(event.getMethodKey());
        }
        Thread target = event.getTargetThread();
        String threadName = target == null ? "unknown" : target.getName();
        LogCollectInternalLogger.warn("Handler {} timed out after {}ms, interrupted thread={}",
                event.getPhase(),
                Long.valueOf(event.getTimeoutMs()),
                threadName);
    }

    private <T> T invokeWithIsolation(Callable<T> callable, boolean transactionIsolation) throws Exception {
        if (transactionIsolation && txWrapper != null) {
            return txWrapper.executeInNewTransaction(callable);
        }
        return callable.call();
    }

    private boolean shouldPropagateDegradeException(LogCollectContext context, Throwable t) {
        if (!(t instanceof LogCollectDegradeException) || context == null || context.getConfig() == null) {
            return false;
        }
        return context.getConfig().isBlockWhenDegradeFail();
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
        } catch (Exception e) {
            LogCollectInternalLogger.warn("onError callback failed", e);
        }
    }

    private CollectMode resolveCollectMode(LogCollectConfig config, LogCollectHandler handler) {
        CollectMode configMode = config == null ? CollectMode.AUTO : config.getCollectMode();
        if (configMode != null && configMode != CollectMode.AUTO) {
            return configMode.resolve();
        }
        CollectMode preferred = handler == null ? CollectMode.AUTO : handler.preferredMode();
        if (preferred != null && preferred != CollectMode.AUTO) {
            return preferred.resolve();
        }
        return CollectMode.AGGREGATE;
    }

    private void forceOpenIfError(LogCollectCircuitBreaker breaker, Throwable throwable) {
        if (!(throwable instanceof Error) || breaker == null) {
            return;
        }
        breaker.forceOpen();
    }

    private final class ServletAsyncCompletion implements LogCollectServletAsyncFinalizer {
        private final LogCollectContext context;
        private final LogCollectHandler handler;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private ServletAsyncCompletion(LogCollectContext context, LogCollectHandler handler) {
            this.context = context;
            this.handler = handler;
        }

        @Override
        public void finish(Throwable error) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (context != null && context.getError() == null && error != null) {
                context.setError(error);
            }
            try {
                finishCollection(context, handler, false);
            } finally {
                try {
                    LogCollectContextManager.release(context);
                } finally {
                    LogCollectConfig config = context == null ? null : context.getConfig();
                    if (metrics != null && config != null && config.isEnableMetrics()) {
                        metrics.recordActiveCollectionEnd();
                    }
                }
            }
        }
    }

    private void forceOpenIfError(LogCollectContext context, Throwable throwable) {
        if (!(throwable instanceof Error) || context == null) {
            return;
        }
        Object circuitBreaker = context.getCircuitBreaker();
        if (circuitBreaker instanceof LogCollectCircuitBreaker) {
            ((LogCollectCircuitBreaker) circuitBreaker).forceOpen();
        }
    }

    private BoundedBufferPolicy.OverflowStrategy parseOverflowStrategy(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY;
        }
        try {
            return BoundedBufferPolicy.OverflowStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY;
        }
    }

    private void frameworkWarn(String message, Throwable error) {
        String detail = error == null ? "" : error.getMessage();
        System.err.printf("[LogCollect-WARN] %s: %s%n", message, detail);
    }

    private static final class ResolvedHandler {
        private final Class<? extends LogCollectHandler> requestedClass;
        private final LogCollectHandler handler;

        private ResolvedHandler(Class<? extends LogCollectHandler> requestedClass, LogCollectHandler handler) {
            this.requestedClass = requestedClass;
            this.handler = handler;
        }

        private boolean matches(Class<? extends LogCollectHandler> currentRequestedClass) {
            return requestedClass == currentRequestedClass;
        }

        private LogCollectHandler getHandler() {
            return handler;
        }
    }

    static class NoopLogCollectHandler implements LogCollectHandler {
        private final AtomicBoolean warned = new AtomicBoolean(false);

        @Override
        public void before(LogCollectContext context) {
            if (!warned.compareAndSet(false, true)) {
                return;
            }
            LogCollectInternalLogger.error(
                    "╔══════════════════════════════════════════════╗\n"
                            + "║ [LogCollect] NoopLogCollectHandler 已激活！  ║\n"
                            + "║ 所有 @LogCollect 日志将被丢弃！             ║\n"
                            + "║ 请注册 LogCollectHandler Bean 以启用收集。   ║\n"
                            + "╚══════════════════════════════════════════════╝");
        }

        @Override
        public void appendLog(LogCollectContext context, com.logcollect.api.model.LogEntry entry) {
            incrementNoopDiscarded(context, 1);
        }

        @Override
        public void flushAggregatedLog(LogCollectContext context, com.logcollect.api.model.AggregatedLog aggregatedLog) {
            int dropped = aggregatedLog == null ? 0 : Math.max(aggregatedLog.getEntryCount(), 0);
            incrementNoopDiscarded(context, dropped);
        }

        private void incrementNoopDiscarded(LogCollectContext context, int dropped) {
            if (context == null || dropped <= 0) {
                return;
            }
            context.incrementDiscardedCount(dropped);
            com.logcollect.api.metrics.LogCollectMetrics metrics =
                    context.getAttribute("__metrics", com.logcollect.api.metrics.LogCollectMetrics.class);
            if (metrics == null) {
                metrics = NoopLogCollectMetrics.INSTANCE;
            }
            for (int i = 0; i < dropped; i++) {
                metrics.incrementDiscarded(context.getMethodSignature(), "noop_handler");
            }
        }
    }
}
