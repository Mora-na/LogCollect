package com.logcollect.benchmark.stress.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stress test wiring:
 * - handler beans used by @LogCollect scenarios
 * - thread factory and tunable scenario parameters
 */
@Configuration
public class StressTestConfig {

    @Bean
    public BenchmarkLogCollectHandler benchmarkLogCollectHandler() {
        return new BenchmarkLogCollectHandler();
    }

    @Bean
    public SlowHandler slowHandler() {
        return new SlowHandler();
    }

    @Bean
    public ThreadFactory stressThreadFactory() {
        return new NamedThreadFactory("stress-worker");
    }

    @Bean
    public StressScenarioParameters stressScenarioParameters(
            @Value("${benchmark.stress.max-thread-cap:128}") int maxThreadCap,
            @Value("${benchmark.stress.task-timeout-seconds:60}") int taskTimeoutSeconds) {
        return new StressScenarioParameters(maxThreadCap, taskTimeoutSeconds);
    }

    public static final class StressScenarioParameters {
        private final int maxThreadCap;
        private final int taskTimeoutSeconds;

        public StressScenarioParameters(int maxThreadCap, int taskTimeoutSeconds) {
            this.maxThreadCap = maxThreadCap <= 0 ? 1 : maxThreadCap;
            this.taskTimeoutSeconds = taskTimeoutSeconds <= 0 ? 1 : taskTimeoutSeconds;
        }

        public int getMaxThreadCap() {
            return maxThreadCap;
        }

        public int getTaskTimeoutSeconds() {
            return taskTimeoutSeconds;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + '-' + sequence.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
