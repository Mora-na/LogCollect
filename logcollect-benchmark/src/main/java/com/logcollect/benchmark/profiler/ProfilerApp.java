package com.logcollect.benchmark.profiler;

import com.logcollect.benchmark.support.BenchmarkLoggingBootstrap;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication(scanBasePackages = "com.logcollect.benchmark")
public class ProfilerApp {

    public static void main(String[] args) {
        BenchmarkLoggingBootstrap.ensureLogbackConfig();
        SpringApplication app = new SpringApplication(ProfilerApp.class);
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
    public CommandLineRunner profileRun(ProfilerScenarioRunner runner) {
        return args -> {
            String scenario = "full";
            for (String arg : args) {
                if (arg != null && arg.startsWith("--scenario=")) {
                    scenario = arg.substring("--scenario=".length());
                }
            }
            runner.run(scenario);
        };
    }
}
