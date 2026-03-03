package com.logcollect.benchmark.stress.runner;

import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import com.logcollect.benchmark.stress.scenario.DegradeScenario;
import com.logcollect.benchmark.stress.scenario.IsolatedAppenderScenario;
import com.logcollect.benchmark.stress.scenario.MultiThreadScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class StressTestRunner {

    private static final Logger log = LoggerFactory.getLogger(StressTestRunner.class);

    private final MultiThreadScenario multiThread;
    private final DegradeScenario degrade;
    private final IsolatedAppenderScenario isolatedAppenderScenario;

    public StressTestRunner(MultiThreadScenario multiThread,
                            DegradeScenario degrade,
                            IsolatedAppenderScenario isolatedAppenderScenario) {
        this.multiThread = multiThread;
        this.degrade = degrade;
        this.isolatedAppenderScenario = isolatedAppenderScenario;
    }

    public Map<String, BenchmarkMetricsCollector.BenchmarkResult> runAll(String mode) {
        Map<String, BenchmarkMetricsCollector.BenchmarkResult> results =
                new LinkedHashMap<String, BenchmarkMetricsCollector.BenchmarkResult>();
        boolean isFull = "full".equalsIgnoreCase(mode);

        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║     @LogCollect Stress Test Suite                ║");
        log.info("║     Mode: {}                                     ║", mode);
        log.info("╠══════════════════════════════════════════════════╣");

        log.info("║ Part 1: Framework Overhead (Isolated)            ║");
        log.info("╠══════════════════════════════════════════════════╣");

        results.put("isolated-1t-clean",
                isolatedAppenderScenario.runIsolated(1, isFull ? 200_000 : 20_000,
                        "Order processed, orderId=ORD-001, amount=99.50", false));
        pauseBetweenScenarios();

        results.put("isolated-8t-clean",
                isolatedAppenderScenario.runIsolated(8, isFull ? 200_000 : 20_000,
                        "Order processed, orderId=ORD-001, amount=99.50", false));
        pauseBetweenScenarios();

        results.put("isolated-8t-sensitive",
                isolatedAppenderScenario.runIsolated(8, isFull ? 100_000 : 10_000,
                        "用户手机: 13812345678, 身份证: 110105199001011234", false));
        pauseBetweenScenarios();

        if (isFull) {
            results.put("isolated-32t-clean",
                    isolatedAppenderScenario.runIsolated(32, 100_000,
                            "Order processed, orderId=ORD-001, amount=99.50", false));
            pauseBetweenScenarios();

            results.put("isolated-8t-with-throwable",
                    isolatedAppenderScenario.runIsolated(8, 20_000,
                            "ERROR processing payment", true));
            pauseBetweenScenarios();
        }

        log.info("╠══════════════════════════════════════════════════╣");
        log.info("║ Part 2: End-to-End Throughput (NOP output)       ║");
        log.info("╠══════════════════════════════════════════════════╣");

        results.put("e2e-1t-clean",
                multiThread.run(1, isFull ? 200_000 : 20_000, "clean"));
        pauseBetweenScenarios();

        results.put("e2e-8t-clean",
                multiThread.run(8, isFull ? 200_000 : 20_000, "clean"));
        pauseBetweenScenarios();

        results.put("e2e-8t-sensitive",
                multiThread.run(8, isFull ? 100_000 : 10_000, "sensitive"));
        pauseBetweenScenarios();

        if (isFull) {
            log.info("╠══════════════════════════════════════════════════╣");
            log.info("║ Part 3: Reliability (Degrade/CircuitBreaker)     ║");
            log.info("╠══════════════════════════════════════════════════╣");
            results.put("degrade", degrade.run());
            pauseBetweenScenarios();
        }

        printSummary(results);

        return results;
    }

    private void pauseBetweenScenarios() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printSummary(Map<String, BenchmarkMetricsCollector.BenchmarkResult> results) {
        log.info("╠══════════════════════════════════════════════════╣");
        log.info("║                Results Summary                   ║");
        log.info("╠══════════════════════════════════════════════════╣");
        log.info(String.format("║ %-25s %12s %12s %8s ║",
                "Scenario", "Throughput", "Avg Latency", "GC%"));
        log.info("║ ─────────────────────────────────────────────── ║");

        for (Map.Entry<String, BenchmarkMetricsCollector.BenchmarkResult> entry : results.entrySet()) {
            BenchmarkMetricsCollector.BenchmarkResult result = entry.getValue();
            log.info(String.format("║ %-25s %,10.0f/s %,10.0f ns %6.2f%% ║",
                    truncate(entry.getKey(), 25),
                    Double.valueOf(result.throughput),
                    Double.valueOf(result.avgLatencyNanos),
                    Double.valueOf(result.gcOverheadPercent)));
        }
        log.info("╚══════════════════════════════════════════════════╝");
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen - 2) + "..";
    }
}
