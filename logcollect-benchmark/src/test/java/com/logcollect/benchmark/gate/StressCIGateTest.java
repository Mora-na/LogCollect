package com.logcollect.benchmark.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logcollect.benchmark.stress.StressTestApp;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector.BenchmarkResult;
import com.logcollect.benchmark.stress.runner.StressTestRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(classes = StressTestApp.class, properties = "benchmark.stress.auto-run=false")
@ActiveProfiles("stress")
public class StressCIGateTest {

    private static final String BASELINE_RESOURCE = "benchmark-baseline.json";
    private static final String STRESS_GATE_CONFIG_KEY = "stressGate";
    private static final String GATE_RUNS_OVERRIDE_PROPERTY = "benchmark.stress.gate.runs";
    private static final String BASELINE_PROFILE_PROPERTY = "benchmark.stress.gate.baseline.profile";
    private static final String STRICT_CI_BASELINE_PROPERTY = "benchmark.stress.gate.strictCiBaseline";
    private static final String FRAMEWORK_VERSION_PROPERTY = "benchmark.stress.gate.framework.version";
    private static final String DEFAULT_CI_BASELINE_PROFILE = "github-ubuntu-latest";
    private static final String CI_STRESS_BASELINES_KEY = "ciStressBaselines";

    @Autowired
    private StressTestRunner runner;

    @Test
    void smokeTest_shouldNotRegressAgainstBaseline() {
        StressGateConfig config = loadStressGateConfig();
        List<Map<String, BenchmarkResult>> runs = runSmokeMany(config);
        Map<String, AggregationResult> throughputAggregates = new LinkedHashMap<String, AggregationResult>();

        for (Map.Entry<String, Double> entry : config.throughputBaselines.entrySet()) {
            String scenario = entry.getKey();
            double baseline = entry.getValue().doubleValue();
            List<Double> samples = collectThroughputSamples(runs, scenario);
            AggregationResult aggregated = robustAverage(samples, config);
            throughputAggregates.put(scenario, aggregated);
            double floor = baseline * config.throughputMinMultiplier;

            System.out.printf(
                    "[GATE] throughput[%s] aggregate=%,.0f, baseline=%,.0f, floor=%,.0f, kept=%d/%d, p50=%,.0f, p90=%,.0f, range=[%,.0f, %,.0f], removed=%s%n",
                    scenario,
                    Double.valueOf(aggregated.value),
                    Double.valueOf(baseline),
                    Double.valueOf(floor),
                    Integer.valueOf(aggregated.keptCount),
                    Integer.valueOf(aggregated.totalCount),
                    Double.valueOf(aggregated.p50),
                    Double.valueOf(aggregated.p90),
                    Double.valueOf(aggregated.min),
                    Double.valueOf(aggregated.max),
                    aggregated.removedValues);

            assertTrue(aggregated.value >= floor,
                    String.format(
                            "GATE FAIL: throughput[%s] aggregate=%,.0f < floor=%,.0f (baseline=%,.0f, minMultiplier=%.2f, kept=%d/%d, p50=%,.0f, p90=%,.0f, range=[%,.0f, %,.0f], removed=%s)",
                            scenario,
                            Double.valueOf(aggregated.value),
                            Double.valueOf(floor),
                            Double.valueOf(baseline),
                            Double.valueOf(config.throughputMinMultiplier),
                            Integer.valueOf(aggregated.keptCount),
                            Integer.valueOf(aggregated.totalCount),
                            Double.valueOf(aggregated.p50),
                            Double.valueOf(aggregated.p90),
                            Double.valueOf(aggregated.min),
                            Double.valueOf(aggregated.max),
                            aggregated.removedValues));
        }

        for (Map.Entry<String, Double> entry : config.ratioBaselines.entrySet()) {
            String ratioKey = entry.getKey();
            double baseline = entry.getValue().doubleValue();
            String[] pair = splitRatioKey(ratioKey);
            List<Double> samples = collectRatioSamples(runs, pair[0], pair[1], ratioKey);
            AggregationResult aggregated = robustAverage(samples, config);
            double effectiveMultiplier = config.resolveRatioMinMultiplier(baseline);
            double floor = baseline * effectiveMultiplier;

            System.out.printf(
                    "[GATE] ratio[%s] aggregate=%.2f, baseline=%.2f, floor=%.2f, multiplier=%.2f, kept=%d/%d, p50=%.2f, p90=%.2f, range=[%.2f, %.2f], removed=%s%n",
                    ratioKey,
                    Double.valueOf(aggregated.value),
                    Double.valueOf(baseline),
                    Double.valueOf(floor),
                    Double.valueOf(effectiveMultiplier),
                    Integer.valueOf(aggregated.keptCount),
                    Integer.valueOf(aggregated.totalCount),
                    Double.valueOf(aggregated.p50),
                    Double.valueOf(aggregated.p90),
                    Double.valueOf(aggregated.min),
                    Double.valueOf(aggregated.max),
                    aggregated.removedValues);

            if (aggregated.value >= floor) {
                continue;
            }

            String numeratorScenario = pair[0];
            Double numeratorBaseline = config.throughputBaselines.get(numeratorScenario);
            if (numeratorBaseline != null) {
                AggregationResult numeratorAgg = throughputAggregates.get(numeratorScenario);
                if (numeratorAgg == null) {
                    numeratorAgg = robustAverage(collectThroughputSamples(runs, numeratorScenario), config);
                    throughputAggregates.put(numeratorScenario, numeratorAgg);
                }
                double numeratorFloor = numeratorBaseline * config.throughputMinMultiplier;
                if (numeratorAgg.value >= numeratorFloor) {
                    System.out.printf(
                            "[GATE][WARN] ratio[%s] aggregate=%.2f < floor=%.2f but throughput[%s]=%,.0f >= floor=%,.0f, treat as warning%n",
                            ratioKey,
                            Double.valueOf(aggregated.value),
                            Double.valueOf(floor),
                            numeratorScenario,
                            Double.valueOf(numeratorAgg.value),
                            Double.valueOf(numeratorFloor));
                    continue;
                }
                fail(String.format(
                        "GATE FAIL: ratio[%s] aggregate=%.2f < floor=%.2f (baseline=%.2f, multiplier=%.2f, kept=%d/%d, p50=%.2f, p90=%.2f, range=[%.2f, %.2f], removed=%s); throughput[%s]=%,.0f < floor=%,.0f",
                        ratioKey,
                        Double.valueOf(aggregated.value),
                        Double.valueOf(floor),
                        Double.valueOf(baseline),
                        Double.valueOf(effectiveMultiplier),
                        Integer.valueOf(aggregated.keptCount),
                        Integer.valueOf(aggregated.totalCount),
                        Double.valueOf(aggregated.p50),
                        Double.valueOf(aggregated.p90),
                        Double.valueOf(aggregated.min),
                        Double.valueOf(aggregated.max),
                        aggregated.removedValues,
                        numeratorScenario,
                        Double.valueOf(numeratorAgg.value),
                        Double.valueOf(numeratorFloor)));
            }

            fail(String.format(
                    "GATE FAIL: ratio[%s] aggregate=%.2f < floor=%.2f (baseline=%.2f, multiplier=%.2f, kept=%d/%d, p50=%.2f, p90=%.2f, range=[%.2f, %.2f], removed=%s)",
                    ratioKey,
                    Double.valueOf(aggregated.value),
                    Double.valueOf(floor),
                    Double.valueOf(baseline),
                    Double.valueOf(effectiveMultiplier),
                    Integer.valueOf(aggregated.keptCount),
                    Integer.valueOf(aggregated.totalCount),
                    Double.valueOf(aggregated.p50),
                    Double.valueOf(aggregated.p90),
                    Double.valueOf(aggregated.min),
                    Double.valueOf(aggregated.max),
                    aggregated.removedValues));
        }

        for (Map.Entry<String, Double> entry : config.gcBaselines.entrySet()) {
            String scenario = entry.getKey();
            double baseline = entry.getValue().doubleValue();
            List<Double> samples = collectGcSamples(runs, scenario);
            AggregationResult aggregated = robustAverage(samples, config);

            double ceilingByBaseline = baseline * config.gcMaxMultiplier;
            double floorByAbsolute = config.gcAbsoluteMinPercent;
            double ceiling = Math.min(config.gcAbsoluteMaxPercent, Math.max(floorByAbsolute, ceilingByBaseline));

            System.out.printf(
                    "[GATE] gc[%s] aggregate=%.2f%%, baseline=%.2f%%, ceiling=%.2f%%, kept=%d/%d, p50=%.2f%%, p90=%.2f%%, range=[%.2f%%, %.2f%%], removed=%s%n",
                    scenario,
                    Double.valueOf(aggregated.value),
                    Double.valueOf(baseline),
                    Double.valueOf(ceiling),
                    Integer.valueOf(aggregated.keptCount),
                    Integer.valueOf(aggregated.totalCount),
                    Double.valueOf(aggregated.p50),
                    Double.valueOf(aggregated.p90),
                    Double.valueOf(aggregated.min),
                    Double.valueOf(aggregated.max),
                    aggregated.removedValues);

            assertTrue(aggregated.value <= ceiling,
                    String.format(
                            "GATE FAIL: gc[%s] aggregate=%.2f%% > ceiling=%.2f%% (baseline=%.2f%%, maxMultiplier=%.2f, kept=%d/%d, p50=%.2f%%, p90=%.2f%%, range=[%.2f%%, %.2f%%], removed=%s)",
                            scenario,
                            Double.valueOf(aggregated.value),
                            Double.valueOf(ceiling),
                            Double.valueOf(baseline),
                            Double.valueOf(config.gcMaxMultiplier),
                            Integer.valueOf(aggregated.keptCount),
                            Integer.valueOf(aggregated.totalCount),
                            Double.valueOf(aggregated.p50),
                            Double.valueOf(aggregated.p90),
                            Double.valueOf(aggregated.min),
                            Double.valueOf(aggregated.max),
                            aggregated.removedValues));
        }
    }

    private List<Map<String, BenchmarkResult>> runSmokeMany(StressGateConfig config) {
        List<Map<String, BenchmarkResult>> allRuns = new ArrayList<Map<String, BenchmarkResult>>(config.runs);
        System.out.printf("[GATE] Stress gate runs=%d, aggregation=trimmed-mean (trimEachSide=%d, min-kept=%d)%n",
                Integer.valueOf(config.runs),
                Integer.valueOf(config.trimCountEachSide),
                Integer.valueOf(config.minSamplesAfterFilter));
        for (int i = 0; i < config.runs; i++) {
            allRuns.add(runner.runAll("smoke"));
        }
        return allRuns;
    }

    private List<Double> collectThroughputSamples(List<Map<String, BenchmarkResult>> runs, String scenario) {
        List<Double> values = new ArrayList<Double>(runs.size());
        for (Map<String, BenchmarkResult> run : runs) {
            BenchmarkResult result = run.get(scenario);
            if (result != null && result.throughput > 0.0d) {
                values.add(Double.valueOf(result.throughput));
            }
        }
        return values;
    }

    private List<Double> collectGcSamples(List<Map<String, BenchmarkResult>> runs, String scenario) {
        List<Double> values = new ArrayList<Double>(runs.size());
        for (Map<String, BenchmarkResult> run : runs) {
            BenchmarkResult result = run.get(scenario);
            if (result != null && result.gcOverheadPercent >= 0.0d) {
                values.add(Double.valueOf(result.gcOverheadPercent));
            }
        }
        return values;
    }

    private List<Double> collectRatioSamples(List<Map<String, BenchmarkResult>> runs,
                                             String numeratorScenario,
                                             String denominatorScenario,
                                             String ratioName) {
        List<Double> values = new ArrayList<Double>(runs.size());
        for (Map<String, BenchmarkResult> run : runs) {
            BenchmarkResult numerator = run.get(numeratorScenario);
            BenchmarkResult denominator = run.get(denominatorScenario);
            if (numerator == null || denominator == null || denominator.throughput <= 0.0d) {
                continue;
            }
            values.add(Double.valueOf(numerator.throughput / denominator.throughput));
        }
        if (values.isEmpty()) {
            fail(String.format("[GATE] Ratio '%s' has no valid samples. numerator=%s, denominator=%s",
                    ratioName, numeratorScenario, denominatorScenario));
        }
        return values;
    }

    private AggregationResult robustAverage(List<Double> values, StressGateConfig config) {
        if (values == null || values.isEmpty()) {
            fail("[GATE] No samples found for aggregation.");
        }

        int total = values.size();
        if (total < config.minSamplesAfterFilter) {
            fail(String.format("[GATE] Not enough samples: got=%d, required=%d",
                    Integer.valueOf(total), Integer.valueOf(config.minSamplesAfterFilter)));
        }

        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int maxTrimEachSide = Math.max(0, (total - config.minSamplesAfterFilter) / 2);
        int trimEachSide = Math.min(config.trimCountEachSide, maxTrimEachSide);
        List<Double> removedValues = new ArrayList<Double>();
        for (int i = 0; i < trimEachSide; i++) {
            removedValues.add(sorted.get(i));
        }
        for (int i = total - trimEachSide; i < total; i++) {
            removedValues.add(sorted.get(i));
        }
        List<Double> kept = new ArrayList<Double>(sorted.subList(trimEachSide, total - trimEachSide));

        if (kept.size() < config.minSamplesAfterFilter) {
            kept = new ArrayList<Double>(sorted);
            removedValues = new ArrayList<Double>();
        }

        return new AggregationResult(
                average(kept),
                min(values),
                max(values),
                percentile(sorted, 50.0d),
                percentile(sorted, 90.0d),
                values.size(),
                kept.size(),
                removedValues
        );
    }

    private String[] splitRatioKey(String ratioKey) {
        int slash = ratioKey.indexOf('/');
        if (slash <= 0 || slash >= ratioKey.length() - 1) {
            fail(String.format("[GATE] Invalid ratio key '%s'. Expected format: numerator/denominator", ratioKey));
        }
        return new String[]{ratioKey.substring(0, slash), ratioKey.substring(slash + 1)};
    }

    private static double average(List<Double> values) {
        double sum = 0.0d;
        for (Double value : values) {
            sum += value.doubleValue();
        }
        return sum / values.size();
    }

    private static double min(List<Double> values) {
        double m = Double.POSITIVE_INFINITY;
        for (Double value : values) {
            m = Math.min(m, value.doubleValue());
        }
        return m;
    }

    private static double max(List<Double> values) {
        double m = Double.NEGATIVE_INFINITY;
        for (Double value : values) {
            m = Math.max(m, value.doubleValue());
        }
        return m;
    }

    private static double percentile(List<Double> sortedAscending, double percentile) {
        if (sortedAscending == null || sortedAscending.isEmpty()) {
            return 0.0d;
        }
        if (sortedAscending.size() == 1) {
            return sortedAscending.get(0).doubleValue();
        }
        double clampedPercentile = Math.max(0.0d, Math.min(100.0d, percentile));
        double position = (clampedPercentile / 100.0d) * (sortedAscending.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sortedAscending.get(lower).doubleValue();
        }
        double lowerValue = sortedAscending.get(lower).doubleValue();
        double upperValue = sortedAscending.get(upper).doubleValue();
        double fraction = position - lower;
        return lowerValue + (upperValue - lowerValue) * fraction;
    }

    private StressGateConfig loadStressGateConfig() {
        InputStream stream = StressCIGateTest.class.getClassLoader().getResourceAsStream(BASELINE_RESOURCE);
        if (stream == null) {
            fail("[GATE] benchmark-baseline.json not found.");
        }
        try (InputStream in = stream) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            String runtimeJdkKey = resolveRuntimeJdkKey();
            String baselineProfile = resolveBaselineProfile();
            boolean strictCiBaseline = resolveStrictCiBaseline();

            if (baselineProfile != null && !baselineProfile.isEmpty()) {
                JsonNode profileNode = root.path(CI_STRESS_BASELINES_KEY).path(baselineProfile);
                JsonNode jdkNode = profileNode.path(runtimeJdkKey);
                JsonNode ciNode = jdkNode.path(STRESS_GATE_CONFIG_KEY);
                if (ciNode.isObject()) {
                    validateFrameworkVersion(profileNode.path("_meta"), jdkNode.path("_meta"), baselineProfile, runtimeJdkKey);
                    System.out.printf("[GATE] Loaded CI stressGate baseline profile=%s, jdk=%s%n",
                            baselineProfile, runtimeJdkKey);
                    return StressGateConfig.from(ciNode);
                }

                String message = String.format("[GATE] CI stressGate baseline missing for profile=%s, jdk=%s.",
                        baselineProfile, runtimeJdkKey);
                if (strictCiBaseline) {
                    fail(message + " Run logcollect-benchmark/scripts/update-stress-ci-baseline.sh on the target CI runner and commit benchmark-baseline.json.");
                } else {
                    System.out.printf("[GATE][WARN] %s fallback to legacy baseline.%n", message);
                }
            }

            JsonNode runtimeNode = root.path("jdkBaselines").path(runtimeJdkKey).path("ratios").path(STRESS_GATE_CONFIG_KEY);
            JsonNode fallbackNode = root.path("ratios").path(STRESS_GATE_CONFIG_KEY);
            JsonNode selected = runtimeNode.isObject() ? runtimeNode : fallbackNode;

            if (!selected.isObject()) {
                fail(String.format("[GATE] stressGate baseline config missing for %s and global fallback.", runtimeJdkKey));
            }

            if (runtimeNode.isObject()) {
                System.out.printf("[GATE] Loaded stressGate baseline for %s%n", runtimeJdkKey);
            } else {
                System.out.printf("[GATE][WARN] stressGate baseline for %s missing, fallback to global config.%n",
                        runtimeJdkKey);
            }

            return StressGateConfig.from(selected);
        } catch (Exception ex) {
            fail(String.format("[GATE] Failed to load stressGate baseline config: %s", ex.getMessage()));
            return null;
        }
    }

    private void validateFrameworkVersion(JsonNode profileMeta,
                                          JsonNode jdkMeta,
                                          String profile,
                                          String runtimeJdkKey) {
        String expected = System.getProperty(FRAMEWORK_VERSION_PROPERTY, "").trim();
        if (expected.isEmpty()) {
            return;
        }
        String actual = textValue(jdkMeta, "frameworkVersion");
        if (actual.isEmpty()) {
            actual = textValue(profileMeta, "frameworkVersion");
        }
        if (!actual.isEmpty() && !expected.equals(actual)) {
            System.out.printf(
                    "[GATE][WARN] CI baseline frameworkVersion mismatch: expected=%s, actual=%s (profile=%s, jdk=%s)%n",
                    expected,
                    actual,
                    profile,
                    runtimeJdkKey);
        }
    }

    private static String textValue(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private static String resolveBaselineProfile() {
        String configured = System.getProperty(BASELINE_PROFILE_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        if (isCiEnvironment()) {
            return DEFAULT_CI_BASELINE_PROFILE;
        }
        return "";
    }

    private static boolean resolveStrictCiBaseline() {
        String configured = System.getProperty(STRICT_CI_BASELINE_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return Boolean.parseBoolean(configured.trim());
        }
        return isCiEnvironment();
    }

    private static boolean isCiEnvironment() {
        String ci = System.getenv("CI");
        return ci != null && "true".equalsIgnoreCase(ci.trim());
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

    private static final class AggregationResult {
        private final double value;
        private final double min;
        private final double max;
        private final double p50;
        private final double p90;
        private final int totalCount;
        private final int keptCount;
        private final List<Double> removedValues;

        AggregationResult(double value,
                          double min,
                          double max,
                          double p50,
                          double p90,
                          int totalCount,
                          int keptCount,
                          List<Double> removedValues) {
            this.value = value;
            this.min = min;
            this.max = max;
            this.p50 = p50;
            this.p90 = p90;
            this.totalCount = totalCount;
            this.keptCount = keptCount;
            this.removedValues = removedValues;
        }
    }

    private static final class StressGateConfig {
        private final int runs;
        private final int trimCountEachSide;
        private final int minSamplesAfterFilter;
        private final double throughputMinMultiplier;
        private final double ratioMinMultiplier;
        private final double gcMaxMultiplier;
        private final double gcAbsoluteMinPercent;
        private final double gcAbsoluteMaxPercent;
        private final Map<String, Double> throughputBaselines;
        private final Map<String, Double> ratioBaselines;
        private final Map<String, Double> gcBaselines;

        private StressGateConfig(int runs,
                                 int trimCountEachSide,
                                 int minSamplesAfterFilter,
                                 double throughputMinMultiplier,
                                 double ratioMinMultiplier,
                                 double gcMaxMultiplier,
                                 double gcAbsoluteMinPercent,
                                 double gcAbsoluteMaxPercent,
                                 Map<String, Double> throughputBaselines,
                                 Map<String, Double> ratioBaselines,
                                 Map<String, Double> gcBaselines) {
            this.runs = runs;
            this.trimCountEachSide = trimCountEachSide;
            this.minSamplesAfterFilter = minSamplesAfterFilter;
            this.throughputMinMultiplier = throughputMinMultiplier;
            this.ratioMinMultiplier = ratioMinMultiplier;
            this.gcMaxMultiplier = gcMaxMultiplier;
            this.gcAbsoluteMinPercent = gcAbsoluteMinPercent;
            this.gcAbsoluteMaxPercent = gcAbsoluteMaxPercent;
            this.throughputBaselines = throughputBaselines;
            this.ratioBaselines = ratioBaselines;
            this.gcBaselines = gcBaselines;
        }

        double resolveRatioMinMultiplier(double baselineRatio) {
            if (baselineRatio >= 5.0d) {
                return 0.70d;
            }
            if (baselineRatio >= 3.0d) {
                return ratioMinMultiplier;
            }
            return 0.80d;
        }

        static StressGateConfig from(JsonNode config) {
            JsonNode aggregation = config.path("aggregation");

            int runs = intValue(aggregation, "runs", 5);
            int trimCountEachSide = intValue(aggregation, "trimCountEachSide", -1);
            if (trimCountEachSide < 0) {
                int legacyOutlierRemovals = intValue(aggregation, "maxOutlierRemovals", 2);
                trimCountEachSide = legacyOutlierRemovals > 0 ? 1 : 0;
            }
            int minSamplesAfterFilter = intValue(aggregation, "minSamplesAfterFilter", 3);

            double throughputMinMultiplier = doubleValue(config, "throughputMinMultiplier", 0.80d);
            double ratioMinMultiplier = doubleValue(config, "ratioMinMultiplier", 0.75d);
            double gcMaxMultiplier = doubleValue(config, "gcMaxMultiplier", 4.0d);
            double gcAbsoluteMinPercent = doubleValue(config, "gcAbsoluteMinPercent", 2.0d);
            double gcAbsoluteMaxPercent = doubleValue(config, "gcAbsoluteMaxPercent", 5.0d);

            String runsOverride = System.getProperty(GATE_RUNS_OVERRIDE_PROPERTY);
            if (runsOverride != null && !runsOverride.trim().isEmpty()) {
                try {
                    runs = Math.max(1, Integer.parseInt(runsOverride.trim()));
                } catch (NumberFormatException ignored) {
                    System.out.printf("[GATE][WARN] Invalid %s=%s, fallback to configured runs=%d%n",
                            GATE_RUNS_OVERRIDE_PROPERTY, runsOverride, Integer.valueOf(runs));
                }
            }
            minSamplesAfterFilter = Math.max(1, Math.min(minSamplesAfterFilter, runs));

            Map<String, Double> throughputBaselines = parseDoubleMap(config.path("throughputBaselines"), "throughputBaselines");
            Map<String, Double> ratioBaselines = parseDoubleMap(config.path("ratioBaselines"), "ratioBaselines");
            Map<String, Double> gcBaselines = parseDoubleMap(config.path("gcBaselines"), "gcBaselines");

            if (throughputBaselines.isEmpty()) {
                fail("[GATE] stressGate.throughputBaselines is empty.");
            }
            if (ratioBaselines.isEmpty()) {
                fail("[GATE] stressGate.ratioBaselines is empty.");
            }
            if (gcBaselines.isEmpty()) {
                fail("[GATE] stressGate.gcBaselines is empty.");
            }

            return new StressGateConfig(
                    Math.max(1, runs),
                    Math.max(0, trimCountEachSide),
                    Math.max(1, minSamplesAfterFilter),
                    throughputMinMultiplier,
                    ratioMinMultiplier,
                    gcMaxMultiplier,
                    gcAbsoluteMinPercent,
                    gcAbsoluteMaxPercent,
                    throughputBaselines,
                    ratioBaselines,
                    gcBaselines
            );
        }

        private static Map<String, Double> parseDoubleMap(JsonNode node, String fieldName) {
            Map<String, Double> map = new LinkedHashMap<String, Double>();
            if (!node.isObject()) {
                return map;
            }
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isNumber()) {
                    map.put(field.getKey(), Double.valueOf(field.getValue().asDouble()));
                } else {
                    fail(String.format("[GATE] %s.%s must be numeric", fieldName, field.getKey()));
                }
            }
            return map;
        }

        private static int intValue(JsonNode node, String field, int defaultValue) {
            JsonNode value = node.path(field);
            return value.isInt() ? value.asInt() : defaultValue;
        }

        private static double doubleValue(JsonNode node, String field, double defaultValue) {
            JsonNode value = node.path(field);
            return value.isNumber() ? value.asDouble() : defaultValue;
        }
    }
}
