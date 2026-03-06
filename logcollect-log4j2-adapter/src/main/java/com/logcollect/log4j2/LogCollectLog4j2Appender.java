package com.logcollect.log4j2;

import com.logcollect.api.backpressure.BackpressureAction;
import com.logcollect.api.backpressure.BackpressureCallback;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeReason;
import com.logcollect.api.enums.SamplingStrategy;
import com.logcollect.api.enums.TotalLimitPolicy;
import com.logcollect.api.exception.LogCollectDegradeException;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.metrics.LogCollectMetrics;
import com.logcollect.api.metrics.NoopLogCollectMetrics;
import com.logcollect.api.model.*;
import com.logcollect.api.sanitizer.LogSanitizer;
import com.logcollect.api.transaction.TransactionExecutor;
import com.logcollect.core.buffer.AggregateModeBuffer;
import com.logcollect.core.buffer.AsyncFlushExecutor;
import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import com.logcollect.core.buffer.LogCollectBuffer;
import com.logcollect.core.circuitbreaker.LogCollectCircuitBreaker;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.context.LogCollectIgnoreManager;
import com.logcollect.core.degrade.DegradeFallbackHandler;
import com.logcollect.core.diagnostics.LogCollectDiag;
import com.logcollect.core.internal.LogCollectInternalLogger;
import com.logcollect.core.pipeline.*;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import com.logcollect.core.security.QuickSanitizer;
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
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.ReadOnlyStringMap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Plugin(name = "LogCollect", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class LogCollectLog4j2Appender extends AbstractAppender {

    private static final String MDC_KEY = LogCollectContextManager.TRACE_ID_KEY;
    private static final String INTERNAL_LOGGER_PREFIX = "com.logcollect.internal";
    private static final String INTERNAL_LOGGER_PREFIX_V2 = "io.github.morana.logcollect.internal";
    private static final String ATTR_SECURITY_PIPELINE = "__securityPipeline";
    private static final String ATTR_SECURITY_METRICS = "__securityMetrics";
    private static final int MAX_THREAD_NAME_CACHE_SIZE = 1024;
    private static final ConcurrentHashMap<String, String> THREAD_NAME_CACHE =
            new ConcurrentHashMap<String, String>(64);

    private volatile SecurityComponentRegistry securityRegistry;
    private volatile LogCollectMetrics metrics = NoopLogCollectMetrics.INSTANCE;
    private volatile LogCollectPipelineManager pipelineManager;
    private volatile Set<String> requiredMdcKeys = Collections.emptySet();

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

    public void setMetrics(LogCollectMetrics metrics) {
        this.metrics = metrics != null ? metrics : NoopLogCollectMetrics.INSTANCE;
    }

    public void setPipelineManager(LogCollectPipelineManager pipelineManager) {
        this.pipelineManager = pipelineManager;
    }

    public void setRequiredMdcKeys(String[] keys) {
        if (keys == null || keys.length == 0) {
            this.requiredMdcKeys = Collections.emptySet();
            return;
        }
        Set<String> normalized = new LinkedHashSet<String>(keys.length);
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String trimmed = key.trim();
            if (!trimmed.isEmpty() && !MDC_KEY.equals(trimmed)) {
                normalized.add(trimmed);
            }
        }
        this.requiredMdcKeys = normalized.isEmpty() ? Collections.emptySet() : normalized;
    }

    @Override
    public void append(LogEvent event) {
        LogCollectContext context = null;
        try {
            ReadOnlyStringMap contextData = event.getContextData();
            String mdcTraceId = extractTraceId(contextData);
            if (mdcTraceId == null || mdcTraceId.isEmpty()) {
                return;
            }
            context = LogCollectContextManager.get(mdcTraceId);
            if (context == null) {
                return;
            }

            LogCollectConfig config = context.getConfig();
            String methodKey = context.getMethodSignature();
            String loggerName = event.getLoggerName();
            if (isInternalLogger(loggerName)) {
                return;
            }
            String level = event.getLevel() == null ? "INFO" : event.getLevel().name();
            LogCollectDiag.debug("Appender received event: logger=%s level=%s", loggerName, level);

            LogCollectMetrics m = resolveMetrics(config);

            if (LogCollectIgnoreManager.isIgnored()) {
                context.incrementDiscardedCount();
                m.incrementDiscarded(methodKey, "logcollect_ignore");
                return;
            }
            if (!isLevelAllowed(event.getLevel(), context.getMinLevelInt())) {
                context.incrementDiscardedCount();
                m.incrementDiscarded(methodKey, "level_filter");
                return;
            }
            if (context.isLoggerExcluded(loggerName)) {
                context.incrementDiscardedCount();
                m.incrementDiscarded(methodKey, "logger_filter");
                return;
            }
            if (config != null && config.isPipelineEnabled()
                    && context.getPipelineQueue() != null
                    && pipelineManager != null) {
                appendToPipeline(event, context, level, loggerName, contextData, m);
                return;
            }
            if (!allowByBackpressure(context, level, methodKey, m)) {
                return;
            }

            String rawMessage = resolveRawMessage(event);
            LogCollectHandler handler = context.getHandler();
            if (handler != null) {
                String messageSummary = QuickSanitizer.summarize(rawMessage, 256);
                if (!handler.shouldCollect(context, level, messageSummary)) {
                    context.incrementDiscardedCount();
                    m.incrementDiscarded(methodKey, "handler_filter");
                    return;
                }
            }

            long eventTimestamp = event.getTimeMillis();
            String content = rawMessage;
            String throwable = extractThrowableString(event);
            String threadName = cachedThreadName(event.getThreadName());
            Map<String, String> mdc = extractRelevantMdc(contextData, context);
            Object securityTimer = m.startSecurityTimer();
            SecurityPipeline.ProcessedLogRecord safeRecord = securityProcess(
                    context,
                    config,
                    methodKey,
                    content,
                    level,
                    eventTimestamp,
                    threadName,
                    loggerName,
                    throwable,
                    mdc,
                    m);
            m.stopSecurityTimer(securityTimer, methodKey);

            if (!allowByTotalLimitAndSampling(context, safeRecord.getLevel(), safeRecord.estimateBytes(), methodKey, m)) {
                return;
            }

            Object buf = context.getBuffer();
            if (config.isUseBuffer() && buf instanceof LogCollectBuffer) {
                boolean accepted;
                if (context.getCollectMode() == CollectMode.AGGREGATE && buf instanceof AggregateModeBuffer) {
                    accepted = ((AggregateModeBuffer) buf).offerRaw(context, safeRecord);
                } else {
                    accepted = ((LogCollectBuffer) buf).offer(context, safeRecord.toLogEntry());
                }
                if (accepted) {
                    m.incrementCollected(methodKey, safeRecord.getLevel(), context.getCollectMode().name());
                }
            } else {
                handleDirect(context, safeRecord.toLogEntry(), methodKey, m);
            }
        } catch (Exception e) {
            rethrowDegradeIfNecessary(context, e);
            LogCollectInternalLogger.error("Log4j2 appender error", e);
        } catch (Error error) {
            rethrowDegradeIfNecessary(context, error);
            LogCollectInternalLogger.error("Log4j2 appender fatal error", error);
            throw error;
        }
    }

    private LogCollectMetrics resolveMetrics(LogCollectConfig config) {
        if (config != null && !config.isEnableMetrics()) {
            return NoopLogCollectMetrics.INSTANCE;
        }
        return this.metrics;
    }

    private void appendToPipeline(LogEvent event,
                                  LogCollectContext context,
                                  String level,
                                  String loggerName,
                                  ReadOnlyStringMap contextData,
                                  LogCollectMetrics metrics) {
        if (context.isClosed() || context.isClosing()) {
            context.incrementDiscardedCount();
            metrics.incrementDiscarded(context.getMethodSignature(), "buffer_closed_late_arrival");
            return;
        }
        Object queue = context.getPipelineQueue();
        if (queue == null) {
            return;
        }

        if (queue instanceof PipelineRingBuffer) {
            PipelineRingBuffer ringBuffer = (PipelineRingBuffer) queue;
            long sequence = ringBuffer.tryClaim();
            if (sequence >= 0L) {
                MutableRawLogRecord slot = ringBuffer.getSlot(sequence);
                Map<String, String> relevantMdc = extractRelevantMdc(contextData, context);
                slot.populate(
                        resolveRawMessage(event),
                        level,
                        loggerName,
                        cachedThreadName(event.getThreadName()),
                        event.getTimeMillis(),
                        context.getTraceId(),
                        extractThrowableString(event),
                        relevantMdc);
                ringBuffer.publish(sequence);
                ringBuffer.signalIfWaiting();
                metrics.updatePipelineQueueUtilization(context.getMethodSignature(), ringBuffer.utilization());
                return;
            }

            context.incrementDiscardedCount();
            metrics.incrementPipelineBackpressure(context.getMethodSignature(), level);
            metrics.updatePipelineQueueUtilization(context.getMethodSignature(), ringBuffer.utilization());

            if (isWarnOrAbove(level)) {
                Map<String, String> relevantMdc = extractRelevantMdc(contextData, context);
                RawLogRecord overflow = new RawLogRecord(
                        resolveRawMessage(event),
                        extractThrowableString(event),
                        level,
                        loggerName,
                        cachedThreadName(event.getThreadName()),
                        event.getTimeMillis(),
                        relevantMdc == null ? Collections.emptyMap() : relevantMdc,
                        context);
                if (ringBuffer.offerOverflow(overflow)) {
                    metrics.incrementDiscarded(context.getMethodSignature(), DegradeReason.PIPELINE_BACKPRESSURE.code());
                    ringBuffer.signalIfWaiting();
                    return;
                }
                metrics.incrementDiscarded(context.getMethodSignature(), DegradeReason.PIPELINE_QUEUE_FULL.code());
                DegradeFallbackHandler.handleDegraded(
                        context,
                        DegradeReason.PIPELINE_QUEUE_FULL.code(),
                        Collections.singletonList(overflow.content),
                        overflow.level);
                return;
            }
            metrics.incrementDiscarded(context.getMethodSignature(), DegradeReason.PIPELINE_BACKPRESSURE.code());
            return;
        }

        context.incrementDiscardedCount();
        metrics.incrementPipelineBackpressure(context.getMethodSignature(), level);
        metrics.incrementDiscarded(context.getMethodSignature(), DegradeReason.PIPELINE_BACKPRESSURE.code());
    }

    private SecurityPipeline.ProcessedLogRecord securityProcess(LogCollectContext context,
                                                                LogCollectConfig config,
                                                                String methodKey,
                                                                String content,
                                                                String level,
                                                                long timestamp,
                                                                String threadName,
                                                                String loggerName,
                                                                String throwable,
                                                                Map<String, String> mdc,
                                                                LogCollectMetrics m) {
        SecurityPipeline pipeline = context.getAttribute(ATTR_SECURITY_PIPELINE, SecurityPipeline.class);
        SecurityPipeline.SecurityMetrics secMetrics =
                context.getAttribute(ATTR_SECURITY_METRICS, SecurityPipeline.SecurityMetrics.class);
        if (secMetrics == null) {
            secMetrics = SecurityPipeline.SecurityMetrics.NOOP;
        }
        if (pipeline == null) {
            LogSanitizer sanitizer = resolveSanitizer(config);
            LogMasker masker = resolveMasker(config);
            pipeline = new SecurityPipeline(sanitizer, masker);
            secMetrics = new SecurityPipeline.SecurityMetrics() {
                @Override
                public void onContentSanitized() {
                    m.incrementSanitizeHits(methodKey);
                }

                @Override
                public void onThrowableSanitized() {
                    m.incrementSanitizeHits(methodKey);
                }

                @Override
                public void onContentMasked() {
                    m.incrementMaskHits(methodKey);
                }

                @Override
                public void onThrowableMasked() {
                    m.incrementMaskHits(methodKey);
                }

                @Override
                public void onFastPathHit() {
                    m.incrementFastPathHits(methodKey);
                }
            };
            context.setAttribute(ATTR_SECURITY_PIPELINE, pipeline);
            context.setAttribute(ATTR_SECURITY_METRICS, secMetrics);
        }
        return pipeline.processRawRecord(
                context.getTraceId(),
                content,
                level,
                timestamp,
                threadName,
                loggerName,
                throwable,
                mdc,
                secMetrics);
    }

    private String resolveRawMessage(LogEvent event) {
        if (event == null) {
            return "";
        }
        Message message = event.getMessage();
        if (message == null) {
            return "";
        }
        String template = message.getFormat();
        Object[] args = message.getParameters();
        if ((args == null || args.length == 0) && template != null) {
            return template;
        }
        String formatted = message.getFormattedMessage();
        if (formatted != null) {
            return formatted;
        }
        return template == null ? "" : template;
    }

    private String extractThrowableString(LogEvent event) {
        Throwable thrown = event.getThrown();
        if (thrown == null) {
            return null;
        }
        StringWriter sw = new StringWriter(1024);
        PrintWriter pw = new PrintWriter(sw);
        thrown.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private String extractTraceId(ReadOnlyStringMap contextData) {
        if (contextData == null) {
            return null;
        }
        Object value = contextData.getValue(MDC_KEY);
        return value == null ? null : value.toString();
    }

    private Map<String, String> extractRelevantMdc(ReadOnlyStringMap contextData, LogCollectContext context) {
        if (contextData == null) {
            return null;
        }
        Set<String> staticKeys = requiredMdcKeys;
        if (staticKeys != null && !staticKeys.isEmpty()) {
            Map<String, String> result = new HashMap<String, String>(staticKeys.size() + 1, 1.0f);
            for (String key : staticKeys) {
                Object value = contextData.getValue(key);
                if (value != null) {
                    result.put(key, value.toString());
                }
            }
            return result.isEmpty() ? null : result;
        }

        String[] configured = null;
        if (context != null && context.getConfig() != null) {
            configured = context.getConfig().getLog4j2MdcKeys();
        }
        if (configured == null || configured.length == 0) {
            return null;
        }

        Map<String, String> result = new HashMap<String, String>(configured.length + 1, 1.0f);
        for (String key : configured) {
            if (key == null) {
                continue;
            }
            String trimmed = key.trim();
            if (trimmed.isEmpty() || MDC_KEY.equals(trimmed)) {
                continue;
            }
            Object value = contextData.getValue(trimmed);
            if (value != null) {
                result.put(trimmed, value.toString());
            }
        }
        return result.isEmpty() ? null : result;
    }

    private String cachedThreadName(String value) {
        if (value == null) {
            return "unknown";
        }
        String cached = THREAD_NAME_CACHE.get(value);
        if (cached != null) {
            return cached;
        }
        if (THREAD_NAME_CACHE.size() >= MAX_THREAD_NAME_CACHE_SIZE) {
            return value;
        }
        THREAD_NAME_CACHE.putIfAbsent(value, value);
        String reused = THREAD_NAME_CACHE.get(value);
        return reused == null ? value : reused;
    }

    private String safeIntern(String value) {
        return cachedThreadName(value);
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
        return eventLevel != null && toLevelRank(eventLevel.name()) >= minLevelInt;
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

    private boolean isInternalLogger(String loggerName) {
        if (loggerName == null) {
            return false;
        }
        return loggerName.startsWith(INTERNAL_LOGGER_PREFIX)
                || loggerName.startsWith(INTERNAL_LOGGER_PREFIX_V2);
    }

    private boolean allowByTotalLimitAndSampling(LogCollectContext context,
                                                  String level,
                                                  long estimatedBytes,
                                                  String methodKey,
                                                  LogCollectMetrics m) {
        LogCollectConfig config = context.getConfig();
        String safeLevel = level == null ? "INFO" : level;

        if (isOverTotalLimit(context, config, estimatedBytes)) {
            TotalLimitPolicy policy = config.getTotalLimitPolicy() == null
                    ? TotalLimitPolicy.STOP_COLLECTING
                    : config.getTotalLimitPolicy();
            if (policy == TotalLimitPolicy.DOWNGRADE_LEVEL) {
                if (!isWarnOrAbove(safeLevel)) {
                    markDiscard(context, methodKey, "total_limit_downgrade", m);
                    return false;
                }
            } else if (policy == TotalLimitPolicy.SAMPLE) {
                if (!shouldSample(context, config, safeLevel, true)) {
                    markDiscard(context, methodKey, "total_limit_sampled", m);
                    return false;
                }
            } else {
                markDiscard(context, methodKey, "total_limit_reached", m);
                return false;
            }
        }

        if (!shouldSample(context, config, safeLevel, false)) {
            markDiscard(context, methodKey, "sampled_out", m);
            return false;
        }
        return true;
    }

    private boolean isOverTotalLimit(LogCollectContext context, LogCollectConfig config, long nextBytes) {
        int maxTotalCollect = config == null ? 0 : config.getMaxTotalCollect();
        if (maxTotalCollect > 0 && context.getTotalCollectedCount() >= maxTotalCollect) {
            return true;
        }
        long maxTotalCollectBytes = config == null ? 0L : config.getMaxTotalCollectBytes();
        return maxTotalCollectBytes > 0
                && context.getTotalCollectedBytes() + Math.max(0L, nextBytes) > maxTotalCollectBytes;
    }

    private boolean shouldSample(LogCollectContext context,
                                 LogCollectConfig config,
                                 String level,
                                 boolean totalLimitSampling) {
        if (config == null) {
            return true;
        }
        double rate = normalizeSamplingRate(config.getSamplingRate());
        if (!totalLimitSampling && rate >= 1.0d) {
            return true;
        }
        if (rate <= 0.0d) {
            return false;
        }
        SamplingStrategy strategy = config.getSamplingStrategy() == null
                ? SamplingStrategy.RATE
                : config.getSamplingStrategy();
        switch (strategy) {
            case COUNT:
                return shouldSampleByCount(context, rate);
            case ADAPTIVE:
                return shouldSampleAdaptive(context, level, rate);
            case RATE:
            default:
                return ThreadLocalRandom.current().nextDouble() < rate;
        }
    }

    private boolean shouldSampleByCount(LogCollectContext context, double rate) {
        AtomicLong counter = context.getAttribute("__sampling_counter", AtomicLong.class);
        if (counter == null) {
            counter = new AtomicLong(0L);
            context.setAttribute("__sampling_counter", counter);
        }
        long current = counter.incrementAndGet();
        int interval = (int) Math.max(1L, Math.round(1.0d / rate));
        return current % interval == 0;
    }

    private boolean shouldSampleAdaptive(LogCollectContext context, String level, double rate) {
        Object managerObj = context.getAttribute("__globalBufferManager");
        double utilization = 0.0d;
        if (managerObj instanceof GlobalBufferMemoryManager) {
            utilization = ((GlobalBufferMemoryManager) managerObj).utilization();
        }
        if (utilization < 0.5d) {
            return true;
        }
        if (utilization < 0.8d) {
            return ThreadLocalRandom.current().nextDouble() < rate;
        }
        return isWarnOrAbove(level);
    }

    private boolean allowByBackpressure(LogCollectContext context,
                                         String level,
                                         String methodKey,
                                         LogCollectMetrics m) {
        BackpressureCallback callback = context.getAttribute("__backpressureCallback", BackpressureCallback.class);
        if (callback == null) {
            return true;
        }
        double utilization = 0.0d;
        Object managerObj = context.getAttribute("__globalBufferManager");
        if (managerObj instanceof GlobalBufferMemoryManager) {
            utilization = ((GlobalBufferMemoryManager) managerObj).utilization();
        }
        BackpressureAction action;
        try {
            action = callback.onPressure(utilization);
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Backpressure callback failed, fallback to CONTINUE", e);
            return true;
        }
        if (action == null || action == BackpressureAction.CONTINUE) {
            return true;
        }
        if (action == BackpressureAction.SKIP_DEBUG_INFO) {
            if (isWarnOrAbove(level)) {
                return true;
            }
            context.incrementDiscardedCount();
            m.incrementDiscarded(methodKey, "backpressure_skip_low_level");
            return false;
        }
        context.incrementDiscardedCount();
        m.incrementDiscarded(methodKey, "backpressure_pause");
        return false;
    }

    private double normalizeSamplingRate(double rawRate) {
        if (Double.isNaN(rawRate) || rawRate <= 0.0d) {
            return 0.0d;
        }
        if (rawRate >= 1.0d) {
            return 1.0d;
        }
        return rawRate;
    }

    private void markDiscard(LogCollectContext context,
                             String methodKey,
                             String reason,
                             LogCollectMetrics m) {
        context.incrementDiscardedCount();
        m.incrementDiscarded(methodKey, reason);
    }

    private boolean isWarnOrAbove(String level) {
        if (level == null) {
            return false;
        }
        String v = level.toUpperCase();
        return "WARN".equals(v) || "ERROR".equals(v) || "FATAL".equals(v);
    }

    private void handleDirect(LogCollectContext context,
                              LogEntry entry,
                              String methodKey,
                              LogCollectMetrics m) {
        Runnable writeTask = () -> doHandleDirect(context, entry, methodKey, m);
        if (context.getConfig().isAsync()) {
            AsyncFlushExecutor.submitOrRun(writeTask);
        } else {
            writeTask.run();
        }
    }

    private void doHandleDirect(LogCollectContext context,
                                LogEntry entry,
                                String methodKey,
                                LogCollectMetrics m) {
        LogCollectHandler handler = context.getHandler();
        if (handler == null) {
            return;
        }
        LogCollectCircuitBreaker breaker = getBreaker(context);
        if (breaker != null && !breaker.allowWrite()) {
            context.incrementDiscardedCount();
            notifyDegrade(context, "circuit_open");
            m.incrementDiscarded(methodKey, "circuit_open");
            DegradeFallbackHandler.handleDegraded(
                    context, "circuit_open",
                    Collections.singletonList(entry.getContent()),
                    entry.getLevel());
            return;
        }
        context.incrementCollectedCount();
        context.addCollectedBytes(entry.estimateBytes());
        m.incrementCollected(methodKey, entry.getLevel(), context.getCollectMode().name());

        Object persistTimer = m.startPersistTimer();
        try {
            TransactionExecutor txExecutor = resolveTransactionExecutor(context);
            txExecutor.executeInNewTransaction(() -> {
                if (context.getCollectMode() == CollectMode.AGGREGATE) {
                    String line;
                    try {
                        line = handler.formatLogLine(entry);
                    } catch (Exception e) {
                        notifyError(context, e, "formatLogLine");
                        line = entry.getContent();
                    } catch (Error error) {
                        notifyError(context, error, "formatLogLine");
                        throw error;
                    }
                    AggregatedLog agg = new AggregatedLog(
                            UUID.randomUUID().toString(),
                            line == null ? "" : line,
                            1,
                            estimateStringBytes(line),
                            entry.getLevel(),
                            entry.getTime(),
                            entry.getTime(),
                            false);
                    handler.flushAggregatedLog(context, agg);
                    m.incrementPersisted(methodKey, CollectMode.AGGREGATE.name());
                } else {
                    handler.appendLog(context, entry);
                    m.incrementPersisted(methodKey, CollectMode.SINGLE.name());
                }
            });
            if (breaker != null) {
                breaker.recordSuccess();
            }
        } catch (Exception e) {
            if (breaker != null) {
                breaker.recordFailure();
            }
            context.incrementDiscardedCount();
            notifyError(context, e, "append");
            notifyDegrade(context, "handler_error");
            m.incrementPersistFailed(methodKey);
            m.incrementDegradeTriggered("persist_failed", methodKey);
            DegradeFallbackHandler.handleDegraded(
                    context, "persist_failed",
                    Collections.singletonList(entry.getContent()),
                    entry.getLevel());
        } catch (Error error) {
            if (breaker != null) {
                breaker.recordFailure();
            }
            notifyError(context, error, "append");
            throw error;
        } finally {
            m.stopPersistTimer(persistTimer, methodKey, context.getCollectMode().name());
        }
    }

    private TransactionExecutor resolveTransactionExecutor(LogCollectContext context) {
        if (context == null || context.getConfig() == null || !context.getConfig().isTransactionIsolation()) {
            return TransactionExecutor.DIRECT;
        }
        Object txObj = context.getAttribute("__txWrapper");
        if (txObj instanceof TransactionExecutor) {
            return (TransactionExecutor) txObj;
        }
        return TransactionExecutor.DIRECT;
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
        } catch (Exception e) {
            LogCollectInternalLogger.warn("onDegrade callback failed", e);
        }
    }

    private void notifyError(LogCollectContext context, Throwable error, String phase) {
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

    private long estimateStringBytes(String value) {
        if (value == null) {
            return 0L;
        }
        return 48L + ((long) value.length() << 1);
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
