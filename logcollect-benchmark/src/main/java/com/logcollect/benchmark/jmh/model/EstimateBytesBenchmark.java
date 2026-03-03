package com.logcollect.benchmark.jmh.model;

import com.logcollect.api.model.LogEntry;
import com.logcollect.benchmark.jmh.BenchmarkData;
import org.openjdk.jmh.annotations.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EstimateBytesBenchmark {

    private LogEntry entry;

    @Setup
    public void setup() {
        entry = LogEntry.builder()
                .traceId("trace-estimate")
                .content(BenchmarkData.MSG_LONG)
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .threadName("benchmark-thread")
                .loggerName("com.example.Bench")
                .throwableString(BenchmarkData.THROWABLE_NORMAL)
                .mdcContext(BenchmarkData.MDC_LARGE)
                .build();
    }

    @Benchmark
    public long estimate_cached() {
        return entry.estimateBytes();
    }

    @Benchmark
    public long estimate_recomputeLikeLegacy() {
        return legacyEstimate(entry);
    }

    private long legacyEstimate(LogEntry logEntry) {
        long size = 112;
        size += estimateString(logEntry.getTraceId());
        size += estimateString(logEntry.getContent());
        size += estimateString(logEntry.getLevel());
        size += estimateString(logEntry.getThreadName());
        size += estimateString(logEntry.getLoggerName());
        size += estimateString(logEntry.getThrowableString());
        Map<String, String> mdc = logEntry.getMdcContext();
        if (mdc != null && !mdc.isEmpty()) {
            size += 64;
            for (Map.Entry<String, String> entryRef : mdc.entrySet()) {
                size += 32;
                size += estimateString(entryRef.getKey());
                size += estimateString(entryRef.getValue());
            }
        }
        return size;
    }

    private long estimateString(String value) {
        if (value == null) {
            return 0;
        }
        return 48L + ((long) value.length() << 1);
    }
}
