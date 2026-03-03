package com.logcollect.benchmark.stress.scenario;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.benchmark.stress.config.BenchmarkLogCollectHandler;
import com.logcollect.benchmark.stress.metrics.BenchmarkMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SingleThreadScenario {

    private static final Logger log = LoggerFactory.getLogger(SingleThreadScenario.class);

    @LogCollect(handler = BenchmarkLogCollectHandler.class, maxBufferSize = 500, maxBufferBytes = "10MB")
    public BenchmarkMetricsCollector.BenchmarkResult run(int logs, String messageType) {
        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector();
        String message = resolveMessage(messageType);

        collector.start();
        for (int i = 0; i < logs; i++) {
            log.info("[single] {} idx={}", message, i);
        }
        collector.recordLogs(logs);
        return collector.stop();
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
