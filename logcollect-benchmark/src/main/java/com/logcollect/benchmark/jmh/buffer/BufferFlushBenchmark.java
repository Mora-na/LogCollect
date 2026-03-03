package com.logcollect.benchmark.jmh.buffer;

import com.logcollect.api.model.LogEntry;
import com.logcollect.benchmark.jmh.BenchmarkData;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class BufferFlushBenchmark {

    private ConcurrentLinkedQueue<LogEntry> singleQueue;
    private ConcurrentLinkedQueue<String> aggregateQueue;

    @Setup(Level.Invocation)
    public void setup() {
        singleQueue = new ConcurrentLinkedQueue<LogEntry>();
        aggregateQueue = new ConcurrentLinkedQueue<String>();
        for (int i = 0; i < 200; i++) {
            singleQueue.offer(BenchmarkData.buildEntry(BenchmarkData.MSG_CLEAN, BenchmarkData.MDC_TYPICAL));
            aggregateQueue.offer("line-" + i + " " + BenchmarkData.MSG_CLEAN);
        }
    }

    @Benchmark
    public int singleMode_flushDrain() {
        List<LogEntry> drained = new ArrayList<LogEntry>(singleQueue.size());
        LogEntry entry;
        while ((entry = singleQueue.poll()) != null) {
            drained.add(entry);
        }
        return drained.size();
    }

    @Benchmark
    public int aggregateMode_flushConcat() {
        StringBuilder sb = new StringBuilder(4096);
        int count = 0;
        String line;
        while ((line = aggregateQueue.poll()) != null) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            count++;
        }
        return count + sb.length();
    }
}
