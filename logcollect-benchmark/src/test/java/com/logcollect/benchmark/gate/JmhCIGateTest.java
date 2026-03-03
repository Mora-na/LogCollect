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

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JmhCIGateTest {

    @Test
    void securityPipeline_shouldNotExceedBudget() throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(".*SecurityPipelineBenchmark\\.pipeline_cleanMessage_emptyMdc")
                .include(".*SecurityPipelineBenchmark\\.pipeline_sensitiveMessage_typicalMdc")
                .mode(Mode.AverageTime)
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(1))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(1))
                .forks(1)
                .jvmArgsAppend("-Xms256m", "-Xmx256m")
                .build();

        Collection<RunResult> results = new Runner(opt).run();

        for (RunResult result : results) {
            double avgNanos = result.getPrimaryResult().getScore();
            String benchName = result.getParams().getBenchmark();
            double budget = benchName.contains("pipeline_sensitiveMessage_typicalMdc")
                    ? 15_000.0d
                    : 10_000.0d;
            assertTrue(avgNanos < budget,
                    String.format("GATE FAIL: %s = %.0f ns (budget: %,.0f ns)", benchName, avgNanos, budget));
        }
    }

    @Test
    void reflectionVsInterface_interfaceMustBeFaster() throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(".*ReflectionVsInterfaceBenchmark")
                .mode(Mode.AverageTime)
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(1))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(1))
                .forks(1)
                .build();

        Collection<RunResult> results = new Runner(opt).run();

        double reflectionNs = 0;
        double interfaceNs = 0;
        for (RunResult result : results) {
            String name = result.getParams().getBenchmark();
            double score = result.getPrimaryResult().getScore();
            if (name.contains("reflection_getMethods_everyTime")) {
                reflectionNs = score;
            }
            if (name.contains("interface_virtualDispatch")) {
                interfaceNs = score;
            }
        }

        if (reflectionNs > 0.0d && interfaceNs > 0.0d) {
            double speedup = reflectionNs / interfaceNs;
            assertTrue(speedup > 10.0d,
                    String.format("GATE FAIL: Interface should be >10x faster than reflection. reflection=%.0f ns, interface=%.0f ns, speedup=%.1fx",
                            reflectionNs, interfaceNs, speedup));
        }
    }

    @Test
    void sanitize_cleanMessage_shouldBeFast() throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(".*SanitizeBenchmark\\.sanitize_cleanMessage")
                .mode(Mode.AverageTime)
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(1))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(1))
                .forks(1)
                .build();

        Collection<RunResult> results = new Runner(opt).run();

        for (RunResult result : results) {
            double avgNanos = result.getPrimaryResult().getScore();
            assertTrue(avgNanos < 2_000.0d,
                    String.format("GATE FAIL: clean sanitize = %.0f ns (budget: 2,000 ns)", avgNanos));
        }
    }
}
