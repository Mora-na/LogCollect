package com.logcollect.benchmark.jmh.buffer;

import com.logcollect.api.model.LogEntry;
import com.logcollect.benchmark.jmh.BenchmarkData;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class BufferOfferBenchmark {

    private ConcurrentLinkedQueue<LogEntry> singleQueue;
    private ConcurrentLinkedQueue<String> aggregateQueue;
    private AtomicInteger count;
    private AtomicLong bytes;
    private LogEntry sampleEntry;
    private String sampleFormatted;

    @Setup
    public void setup() {
        singleQueue = new ConcurrentLinkedQueue<LogEntry>();
        aggregateQueue = new ConcurrentLinkedQueue<String>();
        count = new AtomicInteger(0);
        bytes = new AtomicLong(0);
        sampleEntry = BenchmarkData.buildEntry(BenchmarkData.MSG_CLEAN, BenchmarkData.MDC_TYPICAL);
        sampleFormatted = "2025-07-01 12:00:00.123 INFO [thread-1] OrderService - " + BenchmarkData.MSG_CLEAN;
    }

    @Benchmark
    @Threads(1)
    public void singleMode_offer_1thread() {
        singleQueue.offer(sampleEntry);
        count.incrementAndGet();
        bytes.addAndGet(sampleEntry.estimateBytes());
    }

    @Benchmark
    @Threads(8)
    public void singleMode_offer_8threads() {
        singleQueue.offer(sampleEntry);
        count.incrementAndGet();
        bytes.addAndGet(sampleEntry.estimateBytes());
    }

    @Benchmark
    @Threads(1)
    public void aggregateMode_offer_1thread() {
        aggregateQueue.offer(sampleFormatted);
        count.incrementAndGet();
        bytes.addAndGet(sampleFormatted.length() * 2L);
    }

    @Benchmark
    @Threads(8)
    public void aggregateMode_offer_8threads() {
        aggregateQueue.offer(sampleFormatted);
        count.incrementAndGet();
        bytes.addAndGet(sampleFormatted.length() * 2L);
    }
}
