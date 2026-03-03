package com.logcollect.benchmark.stress.config;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.AggregatedLog;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal overhead handler used for framework-only stress tests.
 */
public class BenchmarkLogCollectHandler implements LogCollectHandler {

    private final AtomicLong appendCount = new AtomicLong(0);
    private final AtomicLong flushCount = new AtomicLong(0);
    private final AtomicLong totalChars = new AtomicLong(0);

    @Override
    public CollectMode preferredMode() {
        return CollectMode.AUTO;
    }

    @Override
    public void before(LogCollectContext context) {
        if (context != null && context.getTraceId() != null && context.getTraceId().length() >= 8) {
            context.setBusinessId("BENCH-" + context.getTraceId().substring(0, 8));
        }
    }

    @Override
    public void appendLog(LogCollectContext context, LogEntry entry) {
        appendCount.incrementAndGet();
    }

    @Override
    public void flushAggregatedLog(LogCollectContext context, AggregatedLog aggregatedLog) {
        flushCount.incrementAndGet();
        if (aggregatedLog != null && aggregatedLog.getContent() != null) {
            totalChars.addAndGet(aggregatedLog.getContent().length());
        }
    }

    @Override
    public void after(LogCollectContext context) {
    }

    public long getAppendCount() {
        return appendCount.get();
    }

    public long getFlushCount() {
        return flushCount.get();
    }

    public long getTotalChars() {
        return totalChars.get();
    }

    public void reset() {
        appendCount.set(0);
        flushCount.set(0);
        totalChars.set(0);
    }
}
