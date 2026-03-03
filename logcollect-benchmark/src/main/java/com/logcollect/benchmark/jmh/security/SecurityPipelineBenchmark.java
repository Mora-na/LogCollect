package com.logcollect.benchmark.jmh.security;

import com.logcollect.api.model.LogEntry;
import com.logcollect.benchmark.jmh.BenchmarkData;
import com.logcollect.core.pipeline.SecurityPipeline;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class SecurityPipelineBenchmark {

    private SecurityPipeline pipeline;
    private SecurityPipeline.SecurityMetrics noopMetrics;

    private LogEntry entryClean;
    private LogEntry entryWithSensitive;
    private LogEntry entryWithThrowable;
    private LogEntry entryWithLargeMdc;

    @Setup
    public void setup() {
        pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker());
        noopMetrics = SecurityPipeline.SecurityMetrics.NOOP;

        StringBuilder cleanBuilder = new StringBuilder(1024);
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                cleanBuilder.append(" | ");
            }
            cleanBuilder.append(BenchmarkData.MSG_CLEAN);
        }
        entryClean = BenchmarkData.buildEntry(cleanBuilder.toString(), BenchmarkData.MDC_EMPTY);
        entryWithSensitive = BenchmarkData.buildEntry(BenchmarkData.MSG_WITH_SENSITIVE, BenchmarkData.MDC_TYPICAL);
        entryWithThrowable = BenchmarkData.buildEntryWithThrowable(BenchmarkData.MSG_WITH_SENSITIVE);
        entryWithLargeMdc = BenchmarkData.buildEntry(BenchmarkData.MSG_CLEAN, BenchmarkData.MDC_LARGE);
    }

    @Benchmark
    public LogEntry pipeline_cleanMessage_emptyMdc() {
        return pipeline.process(entryClean, noopMetrics);
    }

    @Benchmark
    public LogEntry pipeline_sensitiveMessage_typicalMdc() {
        return pipeline.process(entryWithSensitive, noopMetrics);
    }

    /**
     * Control benchmark for throwable path in ratio gate assertions.
     */
    @Benchmark
    public LogEntry pipeline_withThrowable() {
        return pipeline.process(entryWithThrowable, noopMetrics);
    }

    @Benchmark
    public LogEntry pipeline_cleanMessage_largeMdc() {
        return pipeline.process(entryWithLargeMdc, noopMetrics);
    }

    /**
     * Control group that simulates legacy behavior where pipeline is created per call.
     */
    @Benchmark
    public LogEntry pipeline_perCall_newInstance() {
        SecurityPipeline p = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker());
        return p.process(entryWithSensitive, SecurityPipeline.SecurityMetrics.NOOP);
    }
}
