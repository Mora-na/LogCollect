package com.logcollect.benchmark.jmh.buffer;

import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class GlobalMemoryManagerBenchmark {

    private GlobalBufferMemoryManager exact;
    private GlobalBufferMemoryManager striped;

    @Setup
    public void setup() {
        exact = new GlobalBufferMemoryManager(1024L * 1024L * 1024L,
                GlobalBufferMemoryManager.CounterMode.EXACT_CAS);
        striped = new GlobalBufferMemoryManager(1024L * 1024L * 1024L,
                GlobalBufferMemoryManager.CounterMode.STRIPED_LONG_ADDER);
    }

    @Benchmark
    @Threads(1)
    public boolean exact_tryAllocate_release_1thread() {
        boolean ok = exact.tryAllocate(1024);
        exact.release(1024);
        return ok;
    }

    @Benchmark
    @Threads(16)
    public boolean exact_tryAllocate_release_16threads() {
        boolean ok = exact.tryAllocate(1024);
        exact.release(1024);
        return ok;
    }

    @Benchmark
    @Threads(1)
    public boolean striped_tryAllocate_release_1thread() {
        boolean ok = striped.tryAllocate(1024);
        striped.release(1024);
        return ok;
    }

    @Benchmark
    @Threads(16)
    public boolean striped_tryAllocate_release_16threads() {
        boolean ok = striped.tryAllocate(1024);
        striped.release(1024);
        return ok;
    }
}
