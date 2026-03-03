package com.logcollect.benchmark.stress.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects runtime metrics around stress scenarios.
 */
public class BenchmarkMetricsCollector {

    private long startTimeNanos;
    private long gcCountBefore;
    private long gcTimeMsBefore;
    private long heapUsedBefore;
    private final AtomicLong totalLogs = new AtomicLong(0);

    public void start() {
        startTimeNanos = System.nanoTime();
        gcCountBefore = totalGcCount();
        gcTimeMsBefore = totalGcTimeMs();
        heapUsedBefore = heapUsedBytes();
    }

    public void recordLog() {
        totalLogs.incrementAndGet();
    }

    public void recordLogs(long count) {
        totalLogs.addAndGet(count);
    }

    public BenchmarkResult stop() {
        long elapsedNanos = System.nanoTime() - startTimeNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0d;
        long logs = totalLogs.get();
        long gcCount = totalGcCount() - gcCountBefore;
        long gcTimeMs = totalGcTimeMs() - gcTimeMsBefore;
        long heapUsedAfter = heapUsedBytes();

        double safeSeconds = elapsedSeconds <= 0.0d ? 1e-9d : elapsedSeconds;
        long safeLogs = logs <= 0 ? 1 : logs;

        return new BenchmarkResult(
                logs,
                elapsedSeconds,
                logs / safeSeconds,
                (double) elapsedNanos / safeLogs,
                gcCount,
                gcTimeMs,
                (double) gcTimeMs / (safeSeconds * 1000.0d) * 100.0d,
                heapUsedBefore,
                heapUsedAfter
        );
    }

    private long totalGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    private long totalGcTimeMs() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = gc.getCollectionTime();
            if (time > 0) {
                total += time;
            }
        }
        return total;
    }

    private long heapUsedBytes() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        return mem.getHeapMemoryUsage().getUsed();
    }

    public static class BenchmarkResult {
        public final long totalLogs;
        public final double elapsedSeconds;
        public final double throughput;
        public final double avgLatencyNanos;
        public final long gcCount;
        public final long gcTimeMs;
        public final double gcOverheadPercent;
        public final long heapBefore;
        public final long heapAfter;

        public BenchmarkResult(long totalLogs,
                               double elapsedSeconds,
                               double throughput,
                               double avgLatencyNanos,
                               long gcCount,
                               long gcTimeMs,
                               double gcOverheadPercent,
                               long heapBefore,
                               long heapAfter) {
            this.totalLogs = totalLogs;
            this.elapsedSeconds = elapsedSeconds;
            this.throughput = throughput;
            this.avgLatencyNanos = avgLatencyNanos;
            this.gcCount = gcCount;
            this.gcTimeMs = gcTimeMs;
            this.gcOverheadPercent = gcOverheadPercent;
            this.heapBefore = heapBefore;
            this.heapAfter = heapAfter;
        }

        @Override
        public String toString() {
            return String.format(
                    "BenchmarkResult{totalLogs=%d, elapsed=%.2fs, throughput=%.0f logs/s, avgLatency=%.0f ns/log, gcCount=%d, gcTimeMs=%d, gcOverhead=%.2f%%, heapBefore=%d, heapAfter=%d}",
                    totalLogs,
                    elapsedSeconds,
                    throughput,
                    avgLatencyNanos,
                    gcCount,
                    gcTimeMs,
                    gcOverheadPercent,
                    heapBefore,
                    heapAfter
            );
        }
    }
}
