package com.logcollect.benchmark.stress.runner;

import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import com.logcollect.benchmark.stress.scenario.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class StressTestRunner {

    private static final Logger log = LoggerFactory.getLogger(StressTestRunner.class);

    private final SingleThreadScenario singleThread;
    private final MultiThreadScenario multiThread;
    private final BurstScenario burst;
    private final LongRunningScenario longRunning;
    private final NestedScenario nested;
    private final MixedModeScenario mixed;
    private final DegradeScenario degrade;

    public StressTestRunner(SingleThreadScenario singleThread,
                            MultiThreadScenario multiThread,
                            BurstScenario burst,
                            LongRunningScenario longRunning,
                            NestedScenario nested,
                            MixedModeScenario mixed,
                            DegradeScenario degrade) {
        this.singleThread = singleThread;
        this.multiThread = multiThread;
        this.burst = burst;
        this.longRunning = longRunning;
        this.nested = nested;
        this.mixed = mixed;
        this.degrade = degrade;
    }

    public Map<String, BenchmarkMetricsCollector.BenchmarkResult> runAll(String mode) {
        Map<String, BenchmarkMetricsCollector.BenchmarkResult> results =
                new LinkedHashMap<String, BenchmarkMetricsCollector.BenchmarkResult>();
        boolean isFull = "full".equalsIgnoreCase(mode);

        log.info("========== Stress Test Suite: {} mode =========", mode);

        results.put("single-thread-clean",
                singleThread.run(isFull ? 100_000 : 10_000, "clean"));
        gcPause();

        results.put("multi-8-thread-clean",
                multiThread.run(8, isFull ? 100_000 : 10_000, "clean"));
        gcPause();

        results.put("multi-8-thread-sensitive",
                multiThread.run(8, isFull ? 50_000 : 5_000, "sensitive"));
        gcPause();

        if (isFull) {
            results.put("multi-32-thread-clean", multiThread.run(32, 50_000, "clean"));
            gcPause();

            results.put("multi-8-thread-long", multiThread.run(8, 10_000, "long"));
            gcPause();

            results.put("burst", burst.run(20, 2_000, 20));
            gcPause();

            results.put("long-running", longRunning.run(20, 5_000));
            gcPause();

            results.put("nested", nested.run(200, 20));
            gcPause();

            results.put("mixed-mode", mixed.run(10_000, 10_000));
            gcPause();

            results.put("degrade", degrade.run());
            gcPause();
        }

        log.info("========== Results Summary ==========");
        for (Map.Entry<String, BenchmarkMetricsCollector.BenchmarkResult> entry : results.entrySet()) {
            BenchmarkMetricsCollector.BenchmarkResult value = entry.getValue();
            log.info("{}: throughput={} logs/sec, latency={} ns/log, GC={}ms ({}%)",
                    entry.getKey(),
                    String.format("%,.0f", Double.valueOf(value.throughput)),
                    String.format("%,.0f", Double.valueOf(value.avgLatencyNanos)),
                    Long.valueOf(value.gcTimeMs),
                    String.format("%.2f", Double.valueOf(value.gcOverheadPercent)));
        }

        return results;
    }

    private void gcPause() {
        System.gc();
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
