package com.logcollect.benchmark.jmh.security;

import com.logcollect.benchmark.jmh.BenchmarkData;
import com.logcollect.core.security.DefaultLogSanitizer;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
public class SanitizeBenchmark {

    private DefaultLogSanitizer sanitizer;

    @Setup
    public void setup() {
        sanitizer = new DefaultLogSanitizer();
    }

    @Benchmark
    public String sanitize_cleanMessage() {
        return sanitizer.sanitize(BenchmarkData.MSG_CLEAN);
    }

    @Benchmark
    public String sanitize_withInjection() {
        return sanitizer.sanitize(BenchmarkData.MSG_WITH_INJECTION);
    }

    @Benchmark
    public String sanitize_longMessage() {
        return sanitizer.sanitize(BenchmarkData.MSG_LONG);
    }

    @Benchmark
    public String sanitizeThrowable_normal() {
        return sanitizer.sanitizeThrowable(BenchmarkData.THROWABLE_NORMAL);
    }
}
