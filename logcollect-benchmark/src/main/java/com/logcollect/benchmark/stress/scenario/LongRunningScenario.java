package com.logcollect.benchmark.stress.scenario;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.benchmark.stress.config.BenchmarkLogCollectHandler;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LongRunningScenario {

    private static final Logger log = LoggerFactory.getLogger(LongRunningScenario.class);

    @LogCollect(
            handler = BenchmarkLogCollectHandler.class,
            maxBufferSize = 300,
            maxBufferBytes = "8MB",
            maxTotalCollect = 500_000,
            maxTotalCollectBytes = "256MB"
    )
    public BenchmarkMetricsCollector.BenchmarkResult run(int loops, int logsPerLoop) {
        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        collector.start();

        long total = 0;
        for (int l = 0; l < loops; l++) {
            for (int i = 0; i < logsPerLoop; i++) {
                log.info("[long-running] loop={} step={} state=RUNNING", Integer.valueOf(l), Integer.valueOf(i));
                total++;
            }
        }

        collector.recordLogs(total);
        return collector.stop();
    }
}
