package com.logcollect.benchmark.stress.runner;

import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import com.logcollect.benchmark.stress.scenario.BaselineNopScenario;
import com.logcollect.benchmark.stress.scenario.DegradeScenario;
import com.logcollect.benchmark.stress.scenario.IsolatedAppenderScenario;
import com.logcollect.benchmark.stress.scenario.MultiThreadScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class StressTestRunner {

    private static final Logger log = LoggerFactory.getLogger(StressTestRunner.class);
    public static final int SMOKE_ITERATIONS = 100_000;
    public static final int SMOKE_SENSITIVE_ITERATIONS = 50_000;
    public static final int WARMUP_SECONDS = 3;

    private final MultiThreadScenario multiThread;
    private final BaselineNopScenario baselineNopScenario;
    private final DegradeScenario degrade;
    private final IsolatedAppenderScenario isolatedAppenderScenario;

    public StressTestRunner(MultiThreadScenario multiThread,
                            BaselineNopScenario baselineNopScenario,
                            DegradeScenario degrade,
                            IsolatedAppenderScenario isolatedAppenderScenario) {
        this.multiThread = multiThread;
        this.baselineNopScenario = baselineNopScenario;
        this.degrade = degrade;
        this.isolatedAppenderScenario = isolatedAppenderScenario;
    }

    public Map<String, BenchmarkMetricsCollector.BenchmarkResult> runAll(String mode) {
        Map<String, BenchmarkMetricsCollector.BenchmarkResult> results =
                new LinkedHashMap<String, BenchmarkMetricsCollector.BenchmarkResult>();
        boolean isFull = "full".equalsIgnoreCase(mode);
        warmUp();

        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║     @LogCollect Stress Test Suite                ║");
        log.info("║     Mode: {}                                     ║", mode);
        log.info("╠══════════════════════════════════════════════════╣");

        int cleanIterations = isFull ? 200_000 : SMOKE_ITERATIONS;
        int sensitiveIterations = isFull ? 100_000 : SMOKE_SENSITIVE_ITERATIONS;

        log.info("║ Part 0: Baseline (Logger -> NOP)                 ║");
        log.info("╠══════════════════════════════════════════════════╣");
        results.put("baseline-1t-nop", baselineNopScenario.run(1, cleanIterations));
        pauseBetweenScenarios();

        log.info("║ Part 1: Framework Overhead (Isolated)            ║");
        log.info("╠══════════════════════════════════════════════════╣");

        results.put("isolated-1t-clean",
                isolatedAppenderScenario.runIsolated(1, cleanIterations,
                        "Order processed, orderId=ORD-001, amount=99.50", false));
        pauseBetweenScenarios();

        results.put("isolated-8t-clean",
                isolatedAppenderScenario.runIsolated(8, cleanIterations,
                        "Order processed, orderId=ORD-001, amount=99.50", false));
        pauseBetweenScenarios();

        results.put("isolated-8t-sensitive",
                isolatedAppenderScenario.runIsolated(8, sensitiveIterations,
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
                multiThread.run(1, cleanIterations, "clean"));
        pauseBetweenScenarios();

        results.put("e2e-8t-clean",
                multiThread.run(8, cleanIterations, "clean"));
        pauseBetweenScenarios();

        results.put("e2e-8t-sensitive",
                multiThread.run(8, sensitiveIterations, "sensitive"));
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

    private void warmUp() {
        log.info("Warmup start: {}s", Integer.valueOf(WARMUP_SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
        int rounds = 0;
        while (System.nanoTime() < deadline) {
            isolatedAppenderScenario.runIsolated(1, 2_000,
                    "warmup order, id=ORD-WARMUP-001, amount=1.00", false);
            multiThread.run(1, 2_000, "clean");
            rounds++;
        }
        log.info("Warmup done: rounds={}", Integer.valueOf(rounds));
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
        BenchmarkMetricsCollector.BenchmarkResult baseline = results.get("baseline-1t-nop");
        BenchmarkMetricsCollector.BenchmarkResult isolated = results.get("isolated-1t-clean");
        if (baseline != null && isolated != null && baseline.throughput > 0.0d && isolated.throughput > 0.0d) {
            double frameworkNetOverheadNs =
                    (1_000_000_000.0d / isolated.throughput) - (1_000_000_000.0d / baseline.throughput);
            log.info(String.format("║ %-25s %,10.0f ns/log            ║",
                    "framework-net-overhead(1t)",
                    Double.valueOf(frameworkNetOverheadNs)));
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
