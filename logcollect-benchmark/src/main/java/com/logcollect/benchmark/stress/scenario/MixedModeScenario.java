package com.logcollect.benchmark.stress.scenario;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.benchmark.stress.config.BenchmarkLogCollectHandler;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class MixedModeScenario {

    private static final Logger log = LoggerFactory.getLogger(MixedModeScenario.class);

    private final ObjectProvider<MixedModeScenario> self;

    public MixedModeScenario(ObjectProvider<MixedModeScenario> self) {
        this.self = self;
    }

    public BenchmarkMetricsCollector.BenchmarkResult run(int singleLogs, int aggregateLogs) {
        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        collector.start();

        self.getObject().singleMode(singleLogs);
        self.getObject().aggregateMode(aggregateLogs);

        collector.recordLogs((long) singleLogs + aggregateLogs);
        return collector.stop();
    }

    @LogCollect(handler = BenchmarkLogCollectHandler.class,
            collectMode = CollectMode.SINGLE,
            maxBufferSize = 100,
            maxBufferBytes = "2MB")
    public void singleMode(int logs) {
        for (int i = 0; i < logs; i++) {
            log.info("single-mode idx={}", Integer.valueOf(i));
        }
    }

    @LogCollect(handler = BenchmarkLogCollectHandler.class,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 200,
            maxBufferBytes = "4MB")
    public void aggregateMode(int logs) {
        for (int i = 0; i < logs; i++) {
            log.info("aggregate-mode idx={}", Integer.valueOf(i));
        }
    }
}
