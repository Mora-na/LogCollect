package com.logcollect.benchmark.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CI benchmark gates for smoke runs.
 *
 * <p>Design:
 * 1) Correctness first: benchmark must run and produce valid scores.
 * 2) No hard performance thresholds in CI.
 * 3) Fail only on abnormal degradation against baseline, with tiered jitter tolerance:
 *    baseline < 10ns -> allow up to 5.5x slowdown;
 *    baseline 10~100ns -> allow up to 2.2x slowdown;
 *    baseline > 100ns -> allow up to 2.0x slowdown.
 */
public class JmhCIGateTest {

    private static final String JMH_MODE = System.getProperty("jmh.mode", "ci");
    private static final Map<String, Double> BASELINE_SCORES_NS = loadBaselineScoresNs();
    private static final double FAST_BASELINE_NS = 10.0d;
    private static final double MID_BASELINE_NS = 100.0d;
    // GitHub Actions runners are noisy shared environments; keep CI gate slightly relaxed
    // to reduce false-positive regressions caused by infrastructure jitter.
    private static final double FAST_MAX_SLOWDOWN = 5.5d;
    private static final double MID_MAX_SLOWDOWN = 2.2d;
    private static final double SLOW_MAX_SLOWDOWN = 2.0d;

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
        System.out.printf(
                "[GATE] Degradation gate tiers: baseline<%.0fns -> <=%.2fx, %.0f~%.0fns -> <=%.2fx, >%.0fns -> <=%.2fx (baseline metrics=%d)%n",
                Double.valueOf(FAST_BASELINE_NS),
                Double.valueOf(FAST_MAX_SLOWDOWN),
                Double.valueOf(FAST_BASELINE_NS),
                Double.valueOf(MID_BASELINE_NS),
                Double.valueOf(MID_MAX_SLOWDOWN),
                Double.valueOf(MID_BASELINE_NS),
                Double.valueOf(SLOW_MAX_SLOWDOWN),
                Integer.valueOf(BASELINE_SCORES_NS.size()));
    }

    @Test
    void securityPipeline_correctnessAndDegradationGate() throws RunnerException {
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

        assertNoAbnormalRegression("pipeline_cleanMessage_emptyMdc", clean,
                "Security clean path degraded unexpectedly.");
        assertNoAbnormalRegression("pipeline_sensitiveMessage_typicalMdc", sensitive,
                "Security sensitive path degraded unexpectedly.");
        assertNoAbnormalRegression("pipeline_withThrowable", withThrowable,
                "Security throwable path degraded unexpectedly.");
        assertNoAbnormalRegression("pipeline_perCall_newInstance", perCallNew,
                "Pipeline creation path degraded unexpectedly.");
    }

    @Test
    void reflectionVsInterface_correctnessAndDegradationGate() throws RunnerException {
        Options opt = buildOptions(
                "ReflectionVsInterfaceBenchmark\\.reflection_getMethods_everyTime",
                "ReflectionVsInterfaceBenchmark\\.interface_virtualDispatch",
                "ReflectionVsInterfaceBenchmark\\.interface_noop"
        );

        Map<String, Double> scores = runAndCollect(opt);

        double reflection = requireScore(scores, "reflection_getMethods_everyTime");
        double iface = requireScore(scores, "interface_virtualDispatch");
        double noop = requireScore(scores, "interface_noop");

        assertNoAbnormalRegression("reflection_getMethods_everyTime", reflection,
                "Reflection dispatch path degraded unexpectedly.");
        assertNoAbnormalRegression("interface_virtualDispatch", iface,
                "Interface dispatch path degraded unexpectedly.");
        assertNoAbnormalRegression("interface_noop", noop,
                "NOOP dispatch path degraded unexpectedly.");
    }

    @Test
    void sanitize_correctnessAndDegradationGate() throws RunnerException {
        Options opt = buildOptions(
                "SanitizeBenchmark\\.sanitize_cleanMessage",
                "SanitizeBenchmark\\.sanitize_withInjection",
                "SanitizeBenchmark\\.sanitize_longMessage"
        );

        Map<String, Double> scores = runAndCollect(opt);

        double clean = requireScore(scores, "sanitize_cleanMessage");
        double injection = requireScore(scores, "sanitize_withInjection");
        double longMsg = requireScore(scores, "sanitize_longMessage");

        assertNoAbnormalRegression("sanitize_cleanMessage", clean,
                "Sanitize clean path degraded unexpectedly.");
        assertNoAbnormalRegression("sanitize_withInjection", injection,
                "Sanitize injection path degraded unexpectedly.");
        assertNoAbnormalRegression("sanitize_longMessage", longMsg,
                "Sanitize long-message path degraded unexpectedly.");
    }

    @Test
    void mdcCopy_correctnessAndDegradationGate() throws RunnerException {
        Options opt = buildOptions(
                "MdcCopyBenchmark\\.fullCopy_typical",
                "MdcCopyBenchmark\\.lazyCopy_typical_clean",
                "MdcCopyBenchmark\\.fullCopy_large",
                "MdcCopyBenchmark\\.lazyCopy_large_clean"
        );

        Map<String, Double> scores = runAndCollect(opt);

        double fullTypical = requireScore(scores, "fullCopy_typical");
        double lazyTypical = requireScore(scores, "lazyCopy_typical_clean");
        double fullLarge = requireScore(scores, "fullCopy_large");
        double lazyLarge = requireScore(scores, "lazyCopy_large_clean");

        assertNoAbnormalRegression("fullCopy_typical", fullTypical,
                "MDC full copy (typical) degraded unexpectedly.");
        assertNoAbnormalRegression("lazyCopy_typical_clean", lazyTypical,
                "MDC lazy copy (typical) degraded unexpectedly.");
        assertNoAbnormalRegression("fullCopy_large", fullLarge,
                "MDC full copy (large) degraded unexpectedly.");
        assertNoAbnormalRegression("lazyCopy_large_clean", lazyLarge,
                "MDC lazy copy (large) degraded unexpectedly.");
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

    private void assertNoAbnormalRegression(String metric, double actualNs, String hint) {
        Double baselineObj = BASELINE_SCORES_NS.get(metric);
        if (baselineObj == null || baselineObj.doubleValue() <= 0.0d) {
            System.out.printf("[GATE][WARN] Baseline missing for %s, skip degradation gate.%n", metric);
            return;
        }
        double baselineNs = baselineObj.doubleValue();
        double slowdown = actualNs / baselineNs;
        double maxAllowedSlowdown = maxAllowedSlowdownMultiplier(baselineNs);

        System.out.printf("[GATE] Regression %-30s baseline=%,.1f ns, actual=%,.1f ns, slowdown=%.2fx, gate=%s%n",
                metric, Double.valueOf(baselineNs), Double.valueOf(actualNs),
                Double.valueOf(slowdown), gateTierDescription(baselineNs));

        assertTrue(slowdown <= maxAllowedSlowdown,
                gateFailMessage("Abnormal regression " + metric,
                        slowdown,
                        String.format("<= %.2fx latency (%s)",
                                Double.valueOf(maxAllowedSlowdown), gateTierDescription(baselineNs)),
                        hint + String.format(" Baseline=%.1f ns, actual=%.1f ns.", baselineNs, actualNs)));
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

    static double maxAllowedSlowdownMultiplier(double baselineNs) {
        if (baselineNs < FAST_BASELINE_NS) {
            return FAST_MAX_SLOWDOWN;
        }
        if (baselineNs <= MID_BASELINE_NS) {
            return MID_MAX_SLOWDOWN;
        }
        return SLOW_MAX_SLOWDOWN;
    }

    static String gateTierDescription(double baselineNs) {
        if (baselineNs < FAST_BASELINE_NS) {
            return String.format("baseline<%.0fns => <=%.2fx",
                    Double.valueOf(FAST_BASELINE_NS), Double.valueOf(FAST_MAX_SLOWDOWN));
        }
        if (baselineNs <= MID_BASELINE_NS) {
            return String.format("baseline %.0f~%.0fns => <=%.2fx",
                    Double.valueOf(FAST_BASELINE_NS), Double.valueOf(MID_BASELINE_NS), Double.valueOf(MID_MAX_SLOWDOWN));
        }
        return String.format("baseline>%.0fns => <=%.2fx",
                Double.valueOf(MID_BASELINE_NS), Double.valueOf(SLOW_MAX_SLOWDOWN));
    }

    private static Map<String, Double> loadBaselineScoresNs() {
        Map<String, Double> result = new HashMap<String, Double>();
        InputStream stream = JmhCIGateTest.class.getClassLoader().getResourceAsStream("benchmark-baseline.json");
        if (stream == null) {
            System.out.println("[GATE][WARN] benchmark-baseline.json not found, degradation checks disabled.");
            return result;
        }

        try (InputStream in = stream) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            String runtimeJdkKey = resolveRuntimeJdkKey();
            JsonNode jdkBaselines = root.path("jdkBaselines").path(runtimeJdkKey).path("baselines");
            JsonNode baselines = jdkBaselines.isObject() ? jdkBaselines : root.path("baselines");

            if (!baselines.isObject()) {
                return result;
            }

            if (jdkBaselines.isObject()) {
                System.out.printf("[GATE] Loaded baseline set for %s (metrics=%d)%n",
                        runtimeJdkKey, Integer.valueOf(jdkBaselines.size()));
            } else {
                System.out.printf("[GATE][WARN] baseline set for %s not found, fallback to legacy baselines.%n",
                        runtimeJdkKey);
            }

            Iterator<Map.Entry<String, JsonNode>> fields = baselines.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode scoreNode = field.getValue().get("scoreNs");
                if (scoreNode == null || !scoreNode.isNumber()) {
                    continue;
                }
                double scoreNs = scoreNode.asDouble();
                if (scoreNs > 0.0d) {
                    result.put(field.getKey(), Double.valueOf(scoreNs));
                }
            }
        } catch (Exception e) {
            System.out.printf("[GATE][WARN] Failed to load benchmark baseline: %s%n", e.getMessage());
            return new HashMap<String, Double>();
        }
        return result;
    }

    private static String resolveRuntimeJdkKey() {
        String specVersion = System.getProperty("java.specification.version", "");
        if (specVersion == null || specVersion.isEmpty()) {
            return "unknown";
        }
        if (specVersion.startsWith("1.")) {
            specVersion = specVersion.substring(2);
        }
        int dot = specVersion.indexOf('.');
        if (dot > 0) {
            specVersion = specVersion.substring(0, dot);
        }
        try {
            int major = Integer.parseInt(specVersion);
            return "jdk" + major;
        } catch (NumberFormatException ex) {
            return "unknown";
        }
    }
}
