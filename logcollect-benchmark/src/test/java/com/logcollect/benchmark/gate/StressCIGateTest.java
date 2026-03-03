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

    private static final double ISOLATED_1T_FLOOR = 70_000.0d;
    private static final double ISOLATED_8T_FLOOR = 280_000.0d;
    private static final double MIN_SCALING_RATIO_8T = 3.0d;

    @Autowired
    private StressTestRunner runner;

    @Test
    void smokeTest_throughputShouldExceedBaseline() {
        Map<String, BenchmarkResult> results = runner.runAll("smoke");

        BenchmarkResult isolated1t = results.get("isolated-1t-clean");
        if (isolated1t != null) {
            assertTrue(isolated1t.throughput > ISOLATED_1T_FLOOR,
                    String.format("GATE FAIL: isolated-1t throughput = %,.0f (min: %,.0f)",
                            Double.valueOf(isolated1t.throughput), Double.valueOf(ISOLATED_1T_FLOOR)));
        }

        BenchmarkResult isolated8t = results.get("isolated-8t-clean");
        if (isolated8t != null) {
            assertTrue(isolated8t.throughput > ISOLATED_8T_FLOOR,
                    String.format("GATE FAIL: isolated-8t throughput = %,.0f (min: %,.0f)",
                            Double.valueOf(isolated8t.throughput), Double.valueOf(ISOLATED_8T_FLOOR)));
        }

        if (isolated1t != null && isolated8t != null && isolated1t.throughput > 0.0d) {
            double ratio = isolated8t.throughput / isolated1t.throughput;
            assertTrue(ratio > MIN_SCALING_RATIO_8T,
                    String.format("GATE FAIL: isolated 8t scaling ratio = %.2f (min: %.2f)",
                            Double.valueOf(ratio), Double.valueOf(MIN_SCALING_RATIO_8T)));
        }

        for (Map.Entry<String, BenchmarkResult> entry : results.entrySet()) {
            assertTrue(entry.getValue().gcOverheadPercent < 5.0d,
                    String.format("GATE FAIL: %s GC overhead = %.2f%% (max: 5%%)",
                            entry.getKey(),
                            Double.valueOf(entry.getValue().gcOverheadPercent)));
        }
    }
}
