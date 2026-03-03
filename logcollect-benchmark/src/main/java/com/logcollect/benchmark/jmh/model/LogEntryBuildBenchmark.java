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
public class LogEntryBuildBenchmark {

    private long timestamp;
    private Map<String, String> mdc;

    @Setup
    public void setup() {
        timestamp = System.currentTimeMillis();
        mdc = BenchmarkData.MDC_TYPICAL;
    }

    @Benchmark
    public LogEntry build_singleEntry() {
        return LogEntry.builder()
                .traceId("trace-001")
                .content(BenchmarkData.MSG_CLEAN)
                .level("INFO")
                .timestamp(timestamp)
                .threadName("thread-1")
                .loggerName("com.example.Service")
                .throwableString(null)
                .mdcContext(mdc)
                .build();
    }

    @Benchmark
    public LogEntry build_doubleEntry() {
        LogEntry raw = LogEntry.builder()
                .traceId("trace-001")
                .content(BenchmarkData.MSG_CLEAN)
                .level("INFO")
                .timestamp(timestamp)
                .threadName("thread-1")
                .loggerName("com.example.Service")
                .throwableString(null)
                .mdcContext(mdc)
                .build();

        return LogEntry.builder()
                .traceId(raw.getTraceId())
                .content(raw.getContent())
                .level(raw.getLevel())
                .timestamp(raw.getTimestamp())
                .threadName(raw.getThreadName())
                .loggerName(raw.getLoggerName())
                .throwableString(raw.getThrowableString())
                .mdcContext(raw.getMdcContext())
                .build();
    }
}
