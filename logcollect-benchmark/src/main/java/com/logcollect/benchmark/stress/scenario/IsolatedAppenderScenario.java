package com.logcollect.benchmark.stress.scenario;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.Appender;
import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.model.LogCollectContextSnapshot;
import com.logcollect.benchmark.stress.config.BenchmarkLogCollectHandler;
import com.logcollect.benchmark.stress.config.StressTestConfig;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.logback.LogCollectLogbackAppender;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolated appender benchmark that bypasses Logback Logger.callAppenders().
 */
@Component
public class IsolatedAppenderScenario {

    private static final String SYNTHETIC_LOGGER_NAME = "com.logcollect.benchmark.synthetic";

    private final ThreadFactory stressThreadFactory;
    private final StressTestConfig.StressScenarioParameters scenarioParameters;
    private volatile LogCollectLogbackAppender cachedAppender;

    public IsolatedAppenderScenario(ThreadFactory stressThreadFactory,
                                    StressTestConfig.StressScenarioParameters scenarioParameters) {
        this.stressThreadFactory = stressThreadFactory;
        this.scenarioParameters = scenarioParameters;
    }

    public BenchmarkMetricsCollector.BenchmarkResult runIsolated(int threadCount,
                                                                 int logsPerThread,
                                                                 String message) {
        return runIsolated(threadCount, logsPerThread, message, false);
    }

    @LogCollect(handler = BenchmarkLogCollectHandler.class, maxBufferSize = 500, maxBufferBytes = "10MB")
    public BenchmarkMetricsCollector.BenchmarkResult runIsolated(int threadCount,
                                                                 int logsPerThread,
                                                                 String message,
                                                                 boolean withThrowable) {
        int requestedThreads = Math.max(1, threadCount);
        int poolSize = Math.min(requestedThreads, scenarioParameters.getMaxThreadCap());

        LogCollectLogbackAppender appender = resolveAppender();
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        LoggerContextVO loggerContextRemoteView = loggerContext.getLoggerContextRemoteView();
        LogCollectContextSnapshot snapshot = LogCollectContextManager.captureSnapshot();
        String traceId = snapshot.getTraceId();
        if (snapshot.isEmpty() || traceId == null || traceId.isEmpty()) {
            throw new IllegalStateException("No active @LogCollect context found for isolated benchmark");
        }

        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, stressThreadFactory);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(requestedThreads);
        AtomicLong delivered = new AtomicLong(0L);
        AtomicBoolean contextMismatch = new AtomicBoolean(false);

        collector.start();

        for (int t = 0; t < requestedThreads; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                try {
                    LogCollectContextManager.restoreSnapshot(snapshot);
                    String threadTraceId = currentTraceId();
                    if (threadTraceId == null || !traceId.equals(threadTraceId)) {
                        contextMismatch.set(true);
                        return;
                    }
                    Map<String, String> mdc = new HashMap<String, String>(2);
                    mdc.put(LogCollectContextManager.TRACE_ID_KEY, traceId);
                    for (int i = 0; i < logsPerThread; i++) {
                        LoggingEvent event = buildSyntheticEvent(
                                loggerContextRemoteView,
                                mdc,
                                threadIdx,
                                message,
                                i,
                                withThrowable);
                        appender.doAppend(event);
                        delivered.incrementAndGet();
                    }
                } finally {
                    LogCollectContextManager.clearSnapshotContext();
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();

        try {
            endGate.await(scenarioParameters.getTaskTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        pool.shutdown();
        try {
            pool.awaitTermination(scenarioParameters.getTaskTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long expectedLogs = (long) requestedThreads * logsPerThread;
        long deliveredLogs = delivered.get();
        if (contextMismatch.get() || deliveredLogs != expectedLogs) {
            throw new IllegalStateException(
                    "Isolated benchmark context propagation failed: expected="
                            + expectedLogs + ", delivered=" + deliveredLogs);
        }

        collector.recordLogs(deliveredLogs);
        return collector.stop();
    }

    private String currentTraceId() {
        com.logcollect.api.model.LogCollectContext context = LogCollectContextManager.current();
        if (context == null) {
            return null;
        }
        return context.getTraceId();
    }

    private LoggingEvent buildSyntheticEvent(LoggerContextVO loggerContextRemoteView,
                                             Map<String, String> mdc,
                                             int threadIdx,
                                             String message,
                                             int index,
                                             boolean withThrowable) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(SYNTHETIC_LOGGER_NAME);
        event.setLevel(Level.INFO);
        event.setMessage("[Thread-{}] {} idx={}");
        event.setArgumentArray(new Object[]{Integer.valueOf(threadIdx), message, Integer.valueOf(index)});
        event.setTimeStamp(System.currentTimeMillis());
        event.setThreadName(Thread.currentThread().getName());
        event.setLoggerContextRemoteView(loggerContextRemoteView);
        event.setMDCPropertyMap(mdc);

        if (withThrowable) {
            event.setThrowableProxy(new ThrowableProxy(
                    new IllegalStateException("isolated synthetic error idx=" + index)));
        }
        return event;
    }

    private LogCollectLogbackAppender resolveAppender() {
        LogCollectLogbackAppender current = cachedAppender;
        if (current != null) {
            return current;
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = root.iteratorForAppenders();
        while (it.hasNext()) {
            Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender = it.next();
            if (appender instanceof LogCollectLogbackAppender) {
                cachedAppender = (LogCollectLogbackAppender) appender;
                return cachedAppender;
            }
        }
        throw new IllegalStateException("LogCollectLogbackAppender not found on ROOT logger");
    }
}
