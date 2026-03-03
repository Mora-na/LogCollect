package com.logcollect.benchmark.gate;

import com.logcollect.benchmark.stress.StressTestApp;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import com.logcollect.benchmark.stress.runner.StressTestRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = StressTestApp.class)
@ActiveProfiles("stress")
public class StressCIGateTest {

    @Autowired
    private StressTestRunner runner;

    @Test
    void smokeTest_throughputShouldExceedBaseline() {
        Map<String, BenchmarkMetricsCollector.BenchmarkResult> results = runner.runAll("smoke");

        BenchmarkMetricsCollector.BenchmarkResult singleThread = results.get("single-thread-clean");
        if (singleThread != null) {
            assertTrue(singleThread.throughput > 50_000.0d,
                    String.format("GATE FAIL: single-thread throughput = %,.0f logs/sec (min: 50,000)",
                            Double.valueOf(singleThread.throughput)));
        }

        BenchmarkMetricsCollector.BenchmarkResult multiThread = results.get("multi-8-thread-clean");
        if (multiThread != null) {
            assertTrue(multiThread.throughput > 200_000.0d,
                    String.format("GATE FAIL: 8-thread throughput = %,.0f logs/sec (min: 200,000)",
                            Double.valueOf(multiThread.throughput)));
        }

        for (Map.Entry<String, BenchmarkMetricsCollector.BenchmarkResult> entry : results.entrySet()) {
            assertTrue(entry.getValue().gcOverheadPercent < 10.0d,
                    String.format("GATE FAIL: %s GC overhead = %.2f%% (max: 10%%)",
                            entry.getKey(),
                            Double.valueOf(entry.getValue().gcOverheadPercent)));
        }
    }
}
