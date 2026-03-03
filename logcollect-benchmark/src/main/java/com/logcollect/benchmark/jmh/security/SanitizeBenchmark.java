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
    private String cleanTypicalMessage;
    private String injectionTypicalMessage;

    @Setup
    public void setup() {
        sanitizer = new DefaultLogSanitizer();
        StringBuilder sb = new StringBuilder(1024);
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(BenchmarkData.MSG_CLEAN);
        }
        cleanTypicalMessage = sb.toString();
        injectionTypicalMessage = cleanTypicalMessage + " | " + BenchmarkData.MSG_WITH_INJECTION;
    }

    @Benchmark
    public String sanitize_cleanMessage() {
        return sanitizer.sanitize(cleanTypicalMessage);
    }

    @Benchmark
    public String sanitize_withInjection() {
        return sanitizer.sanitize(injectionTypicalMessage);
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
