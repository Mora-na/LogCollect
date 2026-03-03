package com.logcollect.benchmark.stress.scenario;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.benchmark.stress.config.BenchmarkLogCollectHandler;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BurstScenario {

    private static final Logger log = LoggerFactory.getLogger(BurstScenario.class);

    @LogCollect(handler = BenchmarkLogCollectHandler.class, maxBufferSize = 200, maxBufferBytes = "5MB")
    public BenchmarkMetricsCollector.BenchmarkResult run(int rounds, int burstSize, int sleepMs) {
        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        collector.start();

        long total = 0;
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < burstSize; i++) {
                log.info("[burst-{}] order={} amount={}", Integer.valueOf(r), Integer.valueOf(i), Integer.valueOf(100 + i));
                total++;
            }
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        collector.recordLogs(total);
        return collector.stop();
    }
}
