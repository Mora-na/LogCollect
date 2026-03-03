package com.logcollect.benchmark.profiler;

import com.logcollect.benchmark.stress.runner.StressTestRunner;
import org.springframework.stereotype.Component;

@Component
public class ProfilerScenarioRunner {

    private final StressTestRunner runner;

    public ProfilerScenarioRunner(StressTestRunner runner) {
        this.runner = runner;
    }

    public void run(String scenario) {
        if (scenario == null || scenario.trim().isEmpty() || "full".equalsIgnoreCase(scenario)) {
            runner.runAll("full");
            return;
        }
        if ("smoke".equalsIgnoreCase(scenario)) {
            runner.runAll("smoke");
            return;
        }
        // Fallback to full so profiler always has enough data.
        runner.runAll("full");
    }
}
