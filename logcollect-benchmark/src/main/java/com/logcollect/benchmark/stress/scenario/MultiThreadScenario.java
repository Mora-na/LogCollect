package com.logcollect.benchmark.stress.scenario;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.benchmark.stress.config.BenchmarkLogCollectHandler;
import com.logcollect.benchmark.stress.config.StressTestConfig;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import com.logcollect.core.context.LogCollectContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Component
public class MultiThreadScenario {

    private static final Logger log = LoggerFactory.getLogger(MultiThreadScenario.class);
    private final ThreadFactory stressThreadFactory;
    private final StressTestConfig.StressScenarioParameters scenarioParameters;

    public MultiThreadScenario(ThreadFactory stressThreadFactory,
                               StressTestConfig.StressScenarioParameters scenarioParameters) {
        this.stressThreadFactory = stressThreadFactory;
        this.scenarioParameters = scenarioParameters;
    }

    @LogCollect(handler = BenchmarkLogCollectHandler.class, maxBufferSize = 500, maxBufferBytes = "10MB")
    public BenchmarkMetricsCollector.BenchmarkResult run(int threadCount, int logsPerThread, String messageType) {
        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        int requestedThreads = Math.max(1, threadCount);
        int poolSize = Math.min(requestedThreads, scenarioParameters.getMaxThreadCap());
        ExecutorService rawPool = Executors.newFixedThreadPool(poolSize, stressThreadFactory);
        ExecutorService pool = LogCollectContextUtils.wrapExecutorService(rawPool);

        String message = resolveMessage(messageType);
        long totalExpected = (long) requestedThreads * logsPerThread;

        log.info("Starting multi-thread scenario: requestedThreads={}, poolSize={}, logsPerThread={}, total={}",
                Integer.valueOf(requestedThreads),
                Integer.valueOf(poolSize),
                Integer.valueOf(logsPerThread),
                Long.valueOf(totalExpected));

        collector.start();

        List<Future<?>> futures = new ArrayList<Future<?>>(requestedThreads);
        CountDownLatch startGate = new CountDownLatch(1);

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
                    log.info("[Thread-{}] {} idx={}", Integer.valueOf(threadIdx), message, Integer.valueOf(i));
                }
            }));
        }

        startGate.countDown();

        for (Future<?> future : futures) {
            try {
                future.get(scenarioParameters.getTaskTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Task failed", e);
            }
        }

        pool.shutdown();
        try {
            pool.awaitTermination(scenarioParameters.getTaskTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        collector.recordLogs(totalExpected);
        BenchmarkMetricsCollector.BenchmarkResult result = collector.stop();

        log.info("Multi-thread scenario completed: {}", result);
        return result;
    }

    private String resolveMessage(String type) {
        if ("sensitive".equals(type)) {
            return "用户手机: 13812345678, 身份证: 110105199001011234";
        }
        if ("long".equals(type)) {
            StringBuilder sb = new StringBuilder(4000);
            sb.append("Processing batch: ");
            for (int i = 0; i < 50; i++) {
                sb.append("{id:").append(i).append(",phone:138").append(String.format("%08d", i)).append("} ");
            }
            return sb.toString();
        }
        return "Order processed successfully, orderId=ORD-001, amount=99.50";
    }
}
