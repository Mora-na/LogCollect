package com.logcollect.benchmark.stress.scenario;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.benchmark.stress.config.BenchmarkLogCollectHandler;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class NestedScenario {

    private static final Logger log = LoggerFactory.getLogger(NestedScenario.class);

    private final ObjectProvider<NestedScenario> self;

    public NestedScenario(ObjectProvider<NestedScenario> self) {
        this.self = self;
    }

    @LogCollect(handler = BenchmarkLogCollectHandler.class, maxBufferSize = 100)
    public BenchmarkMetricsCollector.BenchmarkResult run(int outerLoops, int innerLoops) {
        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        collector.start();

        long total = 0;
        for (int i = 0; i < outerLoops; i++) {
            log.info("outer-call idx={}", Integer.valueOf(i));
            total++;
            self.getObject().inner(innerLoops);
            total += innerLoops;
        }

        collector.recordLogs(total);
        return collector.stop();
    }

    @LogCollect(handler = BenchmarkLogCollectHandler.class, maxBufferSize = 100)
    public void inner(int loops) {
        for (int i = 0; i < loops; i++) {
            log.info("inner-call idx={}", Integer.valueOf(i));
        }
    }
}
