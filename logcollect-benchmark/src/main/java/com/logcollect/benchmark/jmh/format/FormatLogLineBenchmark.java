package com.logcollect.benchmark.jmh.format;

import com.logcollect.api.format.LogLineDefaults;
import com.logcollect.api.format.LogLinePatternParser;
import com.logcollect.api.model.LogEntry;
import com.logcollect.benchmark.jmh.BenchmarkData;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class FormatLogLineBenchmark {

    private LogEntry entry;
    private String pattern;

    @Setup
    public void setup() {
        entry = BenchmarkData.buildEntry(BenchmarkData.MSG_CLEAN, BenchmarkData.MDC_TYPICAL);
        pattern = LogLineDefaults.getEffectivePattern();
    }

    @Benchmark
    public String format_defaultPattern() {
        return LogLinePatternParser.format(entry, pattern);
    }

    @Benchmark
    public String format_directConcat() {
        return entry.getTimestamp() + " "
                + entry.getLevel() + " ["
                + entry.getThreadName() + "] "
                + entry.getLoggerName() + " - "
                + entry.getContent();
    }
}
