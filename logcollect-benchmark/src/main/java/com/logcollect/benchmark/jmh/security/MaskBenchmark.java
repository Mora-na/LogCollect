package com.logcollect.benchmark.jmh.security;

import com.logcollect.benchmark.jmh.BenchmarkData;
import com.logcollect.core.security.DefaultLogMasker;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class MaskBenchmark {

    private DefaultLogMasker masker;

    @Setup
    public void setup() {
        masker = new DefaultLogMasker();
    }

    @Benchmark
    public String mask_noMatch() {
        return masker.mask(BenchmarkData.MSG_CLEAN);
    }

    @Benchmark
    public String mask_withSensitive() {
        return masker.mask(BenchmarkData.MSG_WITH_SENSITIVE);
    }

    @Benchmark
    public String mask_longWithMultipleHits() {
        return masker.mask(BenchmarkData.MSG_LONG);
    }
}
