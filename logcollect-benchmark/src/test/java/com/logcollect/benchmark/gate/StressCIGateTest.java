package com.logcollect.benchmark.gate;

import com.logcollect.benchmark.stress.StressTestApp;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector.BenchmarkResult;
import com.logcollect.benchmark.stress.runner.StressTestRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = StressTestApp.class, properties = "benchmark.stress.auto-run=false")
@ActiveProfiles("stress")
public class StressCIGateTest {

    @Autowired
    private StressTestRunner runner;

    @Test
    void smokeTest_throughputShouldExceedBaseline() {
        Map<String, BenchmarkResult> results = runner.runAll("smoke");

        BenchmarkResult isolated1t = results.get("isolated-1t-clean");
        if (isolated1t != null) {
            assertTrue(isolated1t.throughput > 200_000.0d,
                    String.format("GATE FAIL: isolated-1t throughput = %,.0f (min: 200,000)",
                            Double.valueOf(isolated1t.throughput)));
        }

        BenchmarkResult isolated8t = results.get("isolated-8t-clean");
        if (isolated8t != null) {
            assertTrue(isolated8t.throughput > 800_000.0d,
                    String.format("GATE FAIL: isolated-8t throughput = %,.0f (min: 800,000)",
                            Double.valueOf(isolated8t.throughput)));
        }

        BenchmarkResult e2e8t = results.get("e2e-8t-clean");
        if (e2e8t != null) {
            assertTrue(e2e8t.throughput > 500_000.0d,
                    String.format("GATE FAIL: e2e-8t throughput = %,.0f (min: 500,000)",
                            Double.valueOf(e2e8t.throughput)));
        }

        for (Map.Entry<String, BenchmarkResult> entry : results.entrySet()) {
            assertTrue(entry.getValue().gcOverheadPercent < 5.0d,
                    String.format("GATE FAIL: %s GC overhead = %.2f%% (max: 5%%)",
                            entry.getKey(),
                            Double.valueOf(entry.getValue().gcOverheadPercent)));
        }
    }
}
