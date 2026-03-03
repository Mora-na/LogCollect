package com.logcollect.benchmark.stress;

import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import com.logcollect.benchmark.stress.runner.StressTestReport;
import com.logcollect.benchmark.stress.runner.StressTestRunner;
import com.logcollect.benchmark.support.BenchmarkLoggingBootstrap;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class StressTestApp {

    public static void main(String[] args) {
        BenchmarkLoggingBootstrap.ensureLogbackConfig();
        SpringApplication app = new SpringApplication(StressTestApp.class);
        app.setAdditionalProfiles("stress");
        Map<String, Object> defaults = new HashMap<String, Object>();
        defaults.put("spring.main.web-application-type", "none");
        defaults.put("logging.level.root", "INFO");
        defaults.put("logging.level.org.springframework", "WARN");
        defaults.put("logging.level.com.logcollect.internal", "WARN");
        app.setDefaultProperties(defaults);
        app.run(args);
    }

    @Bean
    @ConditionalOnProperty(name = "benchmark.stress.auto-run", havingValue = "true", matchIfMissing = true)
    public CommandLineRunner run(StressTestRunner runner) {
        return args -> {
            String mode = "smoke";
            for (String arg : args) {
                if ("--full".equals(arg)) {
                    mode = "full";
                }
            }
            Map<String, BenchmarkMetricsCollector.BenchmarkResult> results = runner.runAll(mode);
            StressTestReport report = new StressTestReport(results);
            System.out.println("BENCHMARK_RESULTS_JSON=" + report.toJson());
        };
    }
}
