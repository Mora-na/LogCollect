package com.logcollect.benchmark.stress.scenario;

import com.logcollect.benchmark.stress.config.StressTestConfig;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Baseline scenario: SLF4J/Logback -> NOP appender, does not pass LogCollect pipeline.
 */
@Component
public class BaselineNopScenario {

    private static final Logger log = LoggerFactory.getLogger("com.logcollect.benchmark.baseline");

    private final ThreadFactory stressThreadFactory;
    private final StressTestConfig.StressScenarioParameters scenarioParameters;

    public BaselineNopScenario(ThreadFactory stressThreadFactory,
                               StressTestConfig.StressScenarioParameters scenarioParameters) {
        this.stressThreadFactory = stressThreadFactory;
        this.scenarioParameters = scenarioParameters;
    }

    public BenchmarkMetricsCollector.BenchmarkResult run(int threadCount, int logsPerThread) {
        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        int requestedThreads = Math.max(1, threadCount);
        int poolSize = Math.min(requestedThreads, scenarioParameters.getMaxThreadCap());
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, stressThreadFactory);
        List<Future<?>> futures = new ArrayList<Future<?>>(requestedThreads);
        CountDownLatch startGate = new CountDownLatch(1);

        long totalExpected = (long) requestedThreads * logsPerThread;
        collector.start();
        for (int t = 0; t < requestedThreads; t++) {
            final int threadIdx = t;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < logsPerThread; i++) {
                    log.info("[baseline-{}] message idx={}", Integer.valueOf(threadIdx), Integer.valueOf(i));
                }
            }));
        }

        startGate.countDown();
        for (Future<?> future : futures) {
            try {
                future.get(scenarioParameters.getTaskTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }

        pool.shutdown();
        try {
            pool.awaitTermination(scenarioParameters.getTaskTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        collector.recordLogs(totalExpected);
        return collector.stop();
    }
}
