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

    @Autowired
    private StressTestRunner runner;

    @Test
    void smokeTest_shouldNotRegressAgainstBaseline() {
        StressGateConfig config = loadStressGateConfig();
        List<Map<String, BenchmarkResult>> runs = runSmokeMany(config);

        for (Map.Entry<String, Double> entry : config.throughputBaselines.entrySet()) {
            String scenario = entry.getKey();
            double baseline = entry.getValue().doubleValue();
            List<Double> samples = collectThroughputSamples(runs, scenario);
            AggregationResult aggregated = robustAverage(samples, config);
            double floor = baseline * config.throughputMinMultiplier;

            assertTrue(aggregated.value >= floor,
                    String.format(
                            "GATE FAIL: throughput[%s] aggregate=%,.0f < floor=%,.0f (baseline=%,.0f, minMultiplier=%.2f, kept=%d/%d, range=[%,.0f, %,.0f], removed=%s)",
                            scenario,
                            Double.valueOf(aggregated.value),
                            Double.valueOf(floor),
                            Double.valueOf(baseline),
                            Double.valueOf(config.throughputMinMultiplier),
                            Integer.valueOf(aggregated.keptCount),
                            Integer.valueOf(aggregated.totalCount),
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
            double floor = baseline * config.ratioMinMultiplier;

            assertTrue(aggregated.value >= floor,
                    String.format(
                            "GATE FAIL: ratio[%s] aggregate=%.2f < floor=%.2f (baseline=%.2f, minMultiplier=%.2f, kept=%d/%d, range=[%.2f, %.2f], removed=%s)",
                            ratioKey,
                            Double.valueOf(aggregated.value),
                            Double.valueOf(floor),
                            Double.valueOf(baseline),
                            Double.valueOf(config.ratioMinMultiplier),
                            Integer.valueOf(aggregated.keptCount),
                            Integer.valueOf(aggregated.totalCount),
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

            assertTrue(aggregated.value <= ceiling,
                    String.format(
                            "GATE FAIL: gc[%s] aggregate=%.2f%% > ceiling=%.2f%% (baseline=%.2f%%, maxMultiplier=%.2f, kept=%d/%d, range=[%.2f%%, %.2f%%], removed=%s)",
                            scenario,
                            Double.valueOf(aggregated.value),
                            Double.valueOf(ceiling),
                            Double.valueOf(baseline),
                            Double.valueOf(config.gcMaxMultiplier),
                            Integer.valueOf(aggregated.keptCount),
                            Integer.valueOf(aggregated.totalCount),
                            Double.valueOf(aggregated.min),
                            Double.valueOf(aggregated.max),
                            aggregated.removedValues));
        }
    }

    private List<Map<String, BenchmarkResult>> runSmokeMany(StressGateConfig config) {
        List<Map<String, BenchmarkResult>> allRuns = new ArrayList<Map<String, BenchmarkResult>>(config.runs);
        System.out.printf("[GATE] Stress gate runs=%d, aggregation=robust-mean (outlier-threshold=%.2f, max-removals=%d)%n",
                Integer.valueOf(config.runs),
                Double.valueOf(config.outlierDeviationThreshold),
                Integer.valueOf(config.maxOutlierRemovals));
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

        double median = median(values);
        double denominator = Math.abs(median) < 1e-9d ? 1.0d : Math.abs(median);

        List<Deviation> deviations = new ArrayList<Deviation>();
        for (int i = 0; i < values.size(); i++) {
            double v = values.get(i).doubleValue();
            double relativeDeviation = Math.abs(v - median) / denominator;
            if (relativeDeviation > config.outlierDeviationThreshold) {
                deviations.add(new Deviation(i, v, relativeDeviation));
            }
        }
        Collections.sort(deviations);

        int maxRemovalsByCount = Math.max(0, total - config.minSamplesAfterFilter);
        int maxRemovals = Math.min(config.maxOutlierRemovals, maxRemovalsByCount);

        Map<Integer, Boolean> removed = new HashMap<Integer, Boolean>();
        List<Double> removedValues = new ArrayList<Double>();
        for (int i = 0; i < deviations.size() && removedValues.size() < maxRemovals; i++) {
            Deviation deviation = deviations.get(i);
            removed.put(Integer.valueOf(deviation.index), Boolean.TRUE);
            removedValues.add(Double.valueOf(deviation.value));
        }

        List<Double> kept = new ArrayList<Double>();
        for (int i = 0; i < values.size(); i++) {
            if (!removed.containsKey(Integer.valueOf(i))) {
                kept.add(values.get(i));
            }
        }

        if (kept.size() < config.minSamplesAfterFilter) {
            kept = new ArrayList<Double>(values);
            removedValues = new ArrayList<Double>();
        }

        return new AggregationResult(
                average(kept),
                min(values),
                max(values),
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

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        int mid = size / 2;
        if ((size & 1) == 0) {
            return (sorted.get(mid - 1).doubleValue() + sorted.get(mid).doubleValue()) / 2.0d;
        }
        return sorted.get(mid).doubleValue();
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
        private final int totalCount;
        private final int keptCount;
        private final List<Double> removedValues;

        AggregationResult(double value,
                          double min,
                          double max,
                          int totalCount,
                          int keptCount,
                          List<Double> removedValues) {
            this.value = value;
            this.min = min;
            this.max = max;
            this.totalCount = totalCount;
            this.keptCount = keptCount;
            this.removedValues = removedValues;
        }
    }

    private static final class Deviation implements Comparable<Deviation> {
        private final int index;
        private final double value;
        private final double relativeDeviation;

        Deviation(int index, double value, double relativeDeviation) {
            this.index = index;
            this.value = value;
            this.relativeDeviation = relativeDeviation;
        }

        @Override
        public int compareTo(Deviation other) {
            return Double.compare(other.relativeDeviation, this.relativeDeviation);
        }
    }

    private static final class StressGateConfig {
        private final int runs;
        private final int maxOutlierRemovals;
        private final int minSamplesAfterFilter;
        private final double outlierDeviationThreshold;
        private final double throughputMinMultiplier;
        private final double ratioMinMultiplier;
        private final double gcMaxMultiplier;
        private final double gcAbsoluteMinPercent;
        private final double gcAbsoluteMaxPercent;
        private final Map<String, Double> throughputBaselines;
        private final Map<String, Double> ratioBaselines;
        private final Map<String, Double> gcBaselines;

        private StressGateConfig(int runs,
                                 int maxOutlierRemovals,
                                 int minSamplesAfterFilter,
                                 double outlierDeviationThreshold,
                                 double throughputMinMultiplier,
                                 double ratioMinMultiplier,
                                 double gcMaxMultiplier,
                                 double gcAbsoluteMinPercent,
                                 double gcAbsoluteMaxPercent,
                                 Map<String, Double> throughputBaselines,
                                 Map<String, Double> ratioBaselines,
                                 Map<String, Double> gcBaselines) {
            this.runs = runs;
            this.maxOutlierRemovals = maxOutlierRemovals;
            this.minSamplesAfterFilter = minSamplesAfterFilter;
            this.outlierDeviationThreshold = outlierDeviationThreshold;
            this.throughputMinMultiplier = throughputMinMultiplier;
            this.ratioMinMultiplier = ratioMinMultiplier;
            this.gcMaxMultiplier = gcMaxMultiplier;
            this.gcAbsoluteMinPercent = gcAbsoluteMinPercent;
            this.gcAbsoluteMaxPercent = gcAbsoluteMaxPercent;
            this.throughputBaselines = throughputBaselines;
            this.ratioBaselines = ratioBaselines;
            this.gcBaselines = gcBaselines;
        }

        static StressGateConfig from(JsonNode config) {
            JsonNode aggregation = config.path("aggregation");

            int runs = intValue(aggregation, "runs", 5);
            int maxOutlierRemovals = intValue(aggregation, "maxOutlierRemovals", 2);
            int minSamplesAfterFilter = intValue(aggregation, "minSamplesAfterFilter", 3);
            double outlierDeviationThreshold = doubleValue(aggregation, "outlierDeviationThreshold", 0.25d);

            double throughputMinMultiplier = doubleValue(config, "throughputMinMultiplier", 0.45d);
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
                    Math.max(0, maxOutlierRemovals),
                    Math.max(1, minSamplesAfterFilter),
                    Math.max(0.0d, outlierDeviationThreshold),
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
