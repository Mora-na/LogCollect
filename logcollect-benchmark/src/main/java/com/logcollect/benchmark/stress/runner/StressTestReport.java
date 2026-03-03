package com.logcollect.benchmark.stress.runner;

import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight report model for CI parsing and console summary.
 */
public class StressTestReport {

    private final Map<String, BenchmarkMetricsCollector.BenchmarkResult> results;

    public StressTestReport(Map<String, BenchmarkMetricsCollector.BenchmarkResult> results) {
        this.results = results == null
                ? new LinkedHashMap<String, BenchmarkMetricsCollector.BenchmarkResult>()
                : new LinkedHashMap<String, BenchmarkMetricsCollector.BenchmarkResult>(results);
    }

    public Map<String, BenchmarkMetricsCollector.BenchmarkResult> getResults() {
        return new LinkedHashMap<String, BenchmarkMetricsCollector.BenchmarkResult>(results);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, BenchmarkMetricsCollector.BenchmarkResult> entry : results.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            BenchmarkMetricsCollector.BenchmarkResult r = entry.getValue();
            sb.append('"').append(entry.getKey()).append('"').append(":{")
                    .append("\"throughput\":").append(r.throughput)
                    .append(",\"avgLatencyNanos\":").append(r.avgLatencyNanos)
                    .append(",\"gcTimeMs\":").append(r.gcTimeMs)
                    .append(",\"gcOverheadPercent\":").append(r.gcOverheadPercent)
                    .append('}');
        }
        sb.append('}');
        return sb.toString();
    }
}
