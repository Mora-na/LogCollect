package com.logcollect.benchmark.jmh.security;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class NeedsSanitizationBenchmark {

    private String cleanValue;
    private String dirtyValue;
    private String longCleanValue;

    @Setup
    public void setup() {
        cleanValue = "normal-trace-id-12345";
        dirtyValue = "injected\nvalue";
        StringBuilder sb = new StringBuilder(1024);
        for (int i = 0; i < 1024; i++) {
            sb.append('A');
        }
        longCleanValue = sb.toString();
    }

    @Benchmark
    public boolean scan_cleanShort() {
        return needsSanitization(cleanValue);
    }

    @Benchmark
    public boolean scan_dirtyEarlyExit() {
        return needsSanitization(dirtyValue);
    }

    @Benchmark
    public boolean scan_cleanLong() {
        return needsSanitization(longCleanValue);
    }

    private static boolean needsSanitization(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t') {
                return true;
            }
            if (c == '<' || c == '>') {
                return true;
            }
            if (c == '\u001B') {
                return true;
            }
            if (c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }
}
