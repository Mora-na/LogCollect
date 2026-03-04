package com.logcollect.benchmark.gate;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CI performance gates for benchmark smoke runs.
 *
 * <p>Design:
 * 1) Ratio-first assertions to neutralize CI host noise.
 * 2) Wide absolute ceilings only for catastrophic regressions.
 * 3) Variance warning output to help diagnose unstable environments.
 */
public class JmhCIGateTest {

    private static final String JMH_MODE = System.getProperty("jmh.mode", "ci");

    private static final int WARMUP_ITERATIONS;
    private static final int WARMUP_TIME_SEC;
    private static final int MEASUREMENT_ITERATIONS;
    private static final int MEASUREMENT_TIME_SEC;
    private static final int FORKS;

    static {
        if ("full".equalsIgnoreCase(JMH_MODE)) {
            WARMUP_ITERATIONS = 5;
            WARMUP_TIME_SEC = 3;
            MEASUREMENT_ITERATIONS = 10;
            MEASUREMENT_TIME_SEC = 3;
            FORKS = 2;
        } else {
            WARMUP_ITERATIONS = 3;
            WARMUP_TIME_SEC = 2;
            MEASUREMENT_ITERATIONS = 5;
            MEASUREMENT_TIME_SEC = 2;
            FORKS = 1;
        }

        System.out.printf("[GATE] JMH mode=%s (warmup=%d x %ds, measure=%d x %ds, forks=%d)%n",
                JMH_MODE, Integer.valueOf(WARMUP_ITERATIONS), Integer.valueOf(WARMUP_TIME_SEC),
                Integer.valueOf(MEASUREMENT_ITERATIONS), Integer.valueOf(MEASUREMENT_TIME_SEC),
                Integer.valueOf(FORKS));
    }

    @Test
    void securityPipeline_ratioAndAbsoluteGate() throws RunnerException {
        Options opt = buildOptions(
                "SecurityPipelineBenchmark\\.pipeline_cleanMessage_emptyMdc",
                "SecurityPipelineBenchmark\\.pipeline_sensitiveMessage_typicalMdc",
                "SecurityPipelineBenchmark\\.pipeline_withThrowable",
                "SecurityPipelineBenchmark\\.pipeline_perCall_newInstance"
        );

        Map<String, Double> scores = runAndCollect(opt);

        double clean = requireScore(scores, "pipeline_cleanMessage_emptyMdc");
        double sensitive = requireScore(scores, "pipeline_sensitiveMessage_typicalMdc");
        double withThrowable = requireScore(scores, "pipeline_withThrowable");
        double perCallNew = requireScore(scores, "pipeline_perCall_newInstance");

        double sensitiveToCleanRatio = sensitive / clean;
        assertRatio("sensitive/clean", sensitiveToCleanRatio, 0.8d, 8.0d,
                "Sensitive path regressed. Check masker rules and regex hot path.");
        warnIfAbove("sensitive/clean", sensitiveToCleanRatio, 5.0d,
                "Above advisory threshold. Check masker rules and regex hot path.");

        double throwableToCleanRatio = withThrowable / clean;
        warnIfAbove("withThrowable/clean", throwableToCleanRatio, 8.0d,
                "Above advisory threshold. Inspect sanitizeThrowable hot path.");

        double throwableToSensitiveRatio = withThrowable / sensitive;
        assertRatio("withThrowable/sensitive", throwableToSensitiveRatio, 1.0d, 6.0d,
                "Throwable path regressed relative to sensitive message control group.");

        double perCallToCleanRatio = perCallNew / clean;
        assertRatio("perCallNew/clean", perCallToCleanRatio, 1.0d, 6.0d,
                "Pipeline reuse optimization may not be effective.");
        warnIfAbove("perCallNew/clean", perCallToCleanRatio, 3.0d,
                "Above advisory threshold. Consider avoiding per-call pipeline construction.");

        assertAbsoluteCeiling("clean", clean, 30_000d,
                "Clean path exceeded catastrophic ceiling.");
        assertAbsoluteCeiling("sensitive", sensitive, 50_000d,
                "Sensitive path exceeded catastrophic ceiling.");
        assertAbsoluteCeiling("withThrowable", withThrowable, 80_000d,
                "Throwable path exceeded catastrophic ceiling.");
    }

    @Test
    void reflectionVsInterface_interfaceMustBeDramaticallyFaster() throws RunnerException {
        Options opt = buildOptions(
                "ReflectionVsInterfaceBenchmark\\.reflection_getMethods_everyTime",
                "ReflectionVsInterfaceBenchmark\\.interface_virtualDispatch",
                "ReflectionVsInterfaceBenchmark\\.interface_noop"
        );

        Map<String, Double> scores = runAndCollect(opt);

        double reflection = requireScore(scores, "reflection_getMethods_everyTime");
        double iface = requireScore(scores, "interface_virtualDispatch");
        double noop = requireScore(scores, "interface_noop");

        double speedup = reflection / iface;
        assertTrue(speedup > 10.0d,
                gateFailMessage("reflection/interface speedup", speedup,
                        "> 10x",
                        String.format("reflection=%.0f ns, interface=%.0f ns, speedup=%.1fx",
                                Double.valueOf(reflection), Double.valueOf(iface), Double.valueOf(speedup))));

        double noopRatio = noop / iface;
        assertRatio("noop/interface", noopRatio, 0.0d, 2.0d,
                "NOOP implementation should not be slower than real implementation.");
    }

    @Test
    void sanitize_cleanPathShouldBeFast() throws RunnerException {
        Options opt = buildOptions(
                "SanitizeBenchmark\\.sanitize_cleanMessage",
                "SanitizeBenchmark\\.sanitize_withInjection",
                "SanitizeBenchmark\\.sanitize_longMessage"
        );

        Map<String, Double> scores = runAndCollect(opt);

        double clean = requireScore(scores, "sanitize_cleanMessage");
        double injection = requireScore(scores, "sanitize_withInjection");
        double longMsg = requireScore(scores, "sanitize_longMessage");

        double injectionToClean = injection / clean;
        assertRatio("injection/clean", injectionToClean, 0.5d, 10.0d,
                "Injection sanitize should be close to clean path.");

        double longToClean = longMsg / clean;
        assertRatio("longMessage/clean", longToClean, 1.0d, 50.0d,
                "Long-message sanitize should scale roughly linearly.");

        assertAbsoluteCeiling("sanitize_clean", clean, 10_000d,
                "Clean sanitize exceeded catastrophic ceiling.");
    }

    @Test
    void mdcCopy_lazyPathShouldBeFaster() throws RunnerException {
        Options opt = buildOptions(
                "MdcCopyBenchmark\\.fullCopy_typical",
                "MdcCopyBenchmark\\.lazyCopy_typical_clean",
                "MdcCopyBenchmark\\.fullCopy_large",
                "MdcCopyBenchmark\\.lazyCopy_large_clean"
        );

        Map<String, Double> scores = runAndCollect(opt);

        if (scores.isEmpty()) {
            System.out.println("[GATE] MDC copy benchmarks not found, skipping.");
            return;
        }

        Double fullTypical = scores.get("fullCopy_typical");
        Double lazyTypical = scores.get("lazyCopy_typical_clean");
        if (fullTypical != null && lazyTypical != null) {
            double speedup = fullTypical.doubleValue() / lazyTypical.doubleValue();
            assertTrue(speedup > 2.0d,
                    gateFailMessage("fullCopy/lazyCopy (typical)", speedup,
                            "> 2x",
                            String.format("full=%.0f ns, lazy=%.0f ns", fullTypical, lazyTypical)));
        }
    }

    private Options buildOptions(String... includes) {
        OptionsBuilder builder = new OptionsBuilder();
        builder.mode(Mode.AverageTime)
                .warmupIterations(WARMUP_ITERATIONS)
                .warmupTime(TimeValue.seconds(WARMUP_TIME_SEC))
                .measurementIterations(MEASUREMENT_ITERATIONS)
                .measurementTime(TimeValue.seconds(MEASUREMENT_TIME_SEC))
                .forks(FORKS)
                .jvmArgsAppend("-Xms512m", "-Xmx512m", "-XX:+UseG1GC");

        for (String include : includes) {
            builder.include(include);
        }
        return builder.build();
    }

    private Map<String, Double> runAndCollect(Options opt) throws RunnerException {
        Collection<RunResult> results = new Runner(opt).run();
        Map<String, Double> scores = new HashMap<String, Double>();

        for (RunResult result : results) {
            String fullName = result.getParams().getBenchmark();
            String simpleName = fullName.substring(fullName.lastIndexOf('.') + 1);
            double score = result.getPrimaryResult().getScore();
            double error = result.getPrimaryResult().getScoreError();
            scores.put(simpleName, Double.valueOf(score));

            System.out.printf("[GATE] %-45s = %,10.1f +- %,.1f ns%n",
                    simpleName, Double.valueOf(score), Double.valueOf(error));

            if (score > 0.0d) {
                double ratio = error / score;
                if (ratio > 0.50d) {
                    System.out.printf("[GATE][WARN] High variance for %s: error/score=%.2f%n",
                            simpleName, Double.valueOf(ratio));
                }
            }
        }

        return scores;
    }

    private double requireScore(Map<String, Double> scores, String name) {
        Double score = scores.get(name);
        if (score == null || score.isNaN() || score.doubleValue() <= 0.0d) {
            fail(String.format("[GATE] Required benchmark '%s' not found or invalid. Available: %s",
                    name, scores.keySet()));
        }
        return score.doubleValue();
    }

    private void assertRatio(String label, double ratio, double minRatio, double maxRatio, String hint) {
        System.out.printf("[GATE] Ratio %-30s = %.2f (expected: %.1f ~ %.1f)%n",
                label, Double.valueOf(ratio), Double.valueOf(minRatio), Double.valueOf(maxRatio));

        assertTrue(ratio >= minRatio && ratio <= maxRatio,
                gateFailMessage("Ratio " + label, ratio,
                        String.format("%.1f ~ %.1f", Double.valueOf(minRatio), Double.valueOf(maxRatio)), hint));
    }

    private void assertAbsoluteCeiling(String label, double actualNs, double ceilingNs, String hint) {
        System.out.printf("[GATE] Ceiling %-28s = %,.0f ns (ceiling: %,.0f ns)%n",
                label, Double.valueOf(actualNs), Double.valueOf(ceilingNs));

        assertTrue(actualNs < ceilingNs,
                gateFailMessage(label, actualNs, String.format("< %,.0f ns", Double.valueOf(ceilingNs)), hint));
    }

    private void warnIfAbove(String label, double actual, double advisoryMax, String hint) {
        if (actual > advisoryMax) {
            System.out.printf("[GATE][WARN] Ratio %-30s = %.2f (advisory <= %.1f). %s%n",
                    label, Double.valueOf(actual), Double.valueOf(advisoryMax), hint);
        }
    }

    private String gateFailMessage(String metric, double actual, String expected, String hint) {
        return String.format(
                "%n================ PERFORMANCE GATE FAILURE ================%n"
                        + "Metric:   %s%n"
                        + "Actual:   %.2f%n"
                        + "Expected: %s%n"
                        + "Hint:     %s%n"
                        + "==========================================================",
                metric,
                Double.valueOf(actual),
                expected,
                hint
        );
    }
}
