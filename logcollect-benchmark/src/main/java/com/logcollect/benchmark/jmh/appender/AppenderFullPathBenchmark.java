package com.logcollect.benchmark.jmh.appender;

import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.api.model.LogEntry;
import com.logcollect.benchmark.jmh.BenchmarkData;
import com.logcollect.core.pipeline.SecurityPipeline;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Simulates append() hot path without Spring wiring.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class AppenderFullPathBenchmark {

    private SecurityPipeline pipeline;
    private LogCollectHandler handler;
    private ConcurrentLinkedQueue<String> queue;

    private LogEntry clean;
    private LogEntry sensitive;

    @Setup
    public void setup() {
        pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker());
        handler = new BenchmarkHandler();
        queue = new ConcurrentLinkedQueue<String>();

        clean = BenchmarkData.buildEntry(BenchmarkData.MSG_CLEAN, BenchmarkData.MDC_TYPICAL);
        sensitive = BenchmarkData.buildEntry(BenchmarkData.MSG_WITH_SENSITIVE, BenchmarkData.MDC_TYPICAL);
    }

    @Benchmark
    public int append_fullPath_clean() {
        LogEntry secured = pipeline.process(clean, SecurityPipeline.SecurityMetrics.NOOP);
        String line = handler.formatLogLine(secured);
        queue.offer(line);
        return line.length();
    }

    @Benchmark
    public int append_fullPath_sensitive() {
        LogEntry secured = pipeline.process(sensitive, SecurityPipeline.SecurityMetrics.NOOP);
        String line = handler.formatLogLine(secured);
        queue.offer(line);
        return line.length();
    }

    @Benchmark
    public int append_directWithoutSecurity() {
        String line = handler.formatLogLine(clean);
        queue.offer(line);
        return line.length();
    }

    private static class BenchmarkHandler implements LogCollectHandler {
        @Override
        public void appendLog(LogCollectContext context, LogEntry entry) {
        }
    }
}
