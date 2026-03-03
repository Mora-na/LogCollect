package com.logcollect.benchmark.stress.scenario;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.benchmark.stress.config.SlowHandler;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DegradeScenario {

    private static final Logger log = LoggerFactory.getLogger(DegradeScenario.class);

    private final SlowHandler handler;

    public DegradeScenario(SlowHandler handler) {
        this.handler = handler;
    }

    @LogCollect(
            handler = SlowHandler.class,
            maxBufferSize = 50,
            degradeStorage = DegradeStorage.LIMITED_MEMORY,
            degradeFailThreshold = 5
    )
    public BenchmarkMetricsCollector.BenchmarkResult run() {
        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        collector.start();
        handler.reset();

        handler.setMode(SlowHandler.Mode.NORMAL);
        for (int i = 0; i < 5000; i++) {
            log.info("normal operation record={}", Integer.valueOf(i));
        }
        log.info("phase1 success={}, fail={}", Long.valueOf(handler.getSuccessCount()), Long.valueOf(handler.getFailCount()));

        handler.setMode(SlowHandler.Mode.INTERMITTENT_FAIL);
        for (int i = 0; i < 5000; i++) {
            log.info("intermittent phase record={}", Integer.valueOf(i));
        }
        log.info("phase2 success={}, fail={}", Long.valueOf(handler.getSuccessCount()), Long.valueOf(handler.getFailCount()));

        handler.setMode(SlowHandler.Mode.TOTAL_FAIL);
        for (int i = 0; i < 5000; i++) {
            log.info("total failure phase record={}", Integer.valueOf(i));
        }
        log.info("phase3 success={}, fail={}", Long.valueOf(handler.getSuccessCount()), Long.valueOf(handler.getFailCount()));

        handler.setMode(SlowHandler.Mode.NORMAL);
        for (int i = 0; i < 5000; i++) {
            log.info("recovery phase record={}", Integer.valueOf(i));
        }
        log.info("phase4 success={}, fail={}", Long.valueOf(handler.getSuccessCount()), Long.valueOf(handler.getFailCount()));

        collector.recordLogs(20_000);
        return collector.stop();
    }
}
