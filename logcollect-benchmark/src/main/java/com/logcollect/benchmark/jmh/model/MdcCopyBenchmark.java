package com.logcollect.benchmark.jmh.model;

import com.logcollect.benchmark.jmh.BenchmarkData;
import org.openjdk.jmh.annotations.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MdcCopyBenchmark {

    private Map<String, String> typicalMdc;
    private Map<String, String> largeMdc;
    private Map<String, String> largeMdcWithDirty;

    @Setup
    public void setup() {
        typicalMdc = BenchmarkData.MDC_TYPICAL;
        largeMdc = new LinkedHashMap<String, String>();
        for (int i = 0; i < 15; i++) {
            largeMdc.put("key" + i, "clean-value-" + i);
        }
        largeMdcWithDirty = BenchmarkData.MDC_LARGE;
    }

    @Benchmark
    public Map<String, String> fullCopy_typical() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(typicalMdc));
    }

    @Benchmark
    public Map<String, String> fullCopy_large() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(largeMdc));
    }

    @Benchmark
    public Map<String, String> lazyCopy_typical_clean() {
        return lazySanitizeMdc(typicalMdc);
    }

    @Benchmark
    public Map<String, String> lazyCopy_large_clean() {
        return lazySanitizeMdc(largeMdc);
    }

    @Benchmark
    public Map<String, String> lazyCopy_large_dirty() {
        return lazySanitizeMdc(largeMdcWithDirty);
    }

    private Map<String, String> lazySanitizeMdc(Map<String, String> original) {
        if (original == null || original.isEmpty()) {
            return original;
        }
        for (Map.Entry<String, String> entry : original.entrySet()) {
            if (needsSanitization(entry.getKey()) || needsSanitization(entry.getValue())) {
                Map<String, String> result = new LinkedHashMap<String, String>(original.size());
                for (Map.Entry<String, String> item : original.entrySet()) {
                    result.put(sanitize(item.getKey()), sanitize(item.getValue()));
                }
                return Collections.unmodifiableMap(result);
            }
        }
        return original;
    }

    private boolean needsSanitization(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == '<' || c == '>' || c == '\u001B') {
                return true;
            }
        }
        return false;
    }

    private String sanitize(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n\\t]", " ");
    }
}
