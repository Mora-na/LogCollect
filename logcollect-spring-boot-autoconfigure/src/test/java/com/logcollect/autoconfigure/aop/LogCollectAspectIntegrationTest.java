package com.logcollect.autoconfigure.aop;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.model.LogCollectContext;
import com.logcollect.autoconfigure.LogCollectAopAutoConfiguration;
import com.logcollect.autoconfigure.LogCollectAutoConfiguration;
import com.logcollect.core.runtime.LogCollectGlobalSwitch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {
                LogCollectAutoConfiguration.class,
                LogCollectAopAutoConfiguration.class,
                LogCollectAspectIntegrationTest.TestConfig.class
        },
        properties = {
                "logcollect.global.enabled=true",
                "logcollect.global.async=false",
                "logcollect.global.buffer.enabled=false",
                "logcollect.global.max-nesting-depth=1"
        }
)
class LogCollectAspectIntegrationTest {

    @Autowired
    private TestService testService;

    @Autowired
    private RecordingHandler recordingHandler;

    @Autowired
    private LogCollectGlobalSwitch globalSwitch;

    @BeforeEach
    void resetState() {
        recordingHandler.beforeCount.set(0);
        recordingHandler.afterCount.set(0);
        globalSwitch.onConfigChange(true);
    }

    @Test
    void around_globalDisabled_proceedsDirectly() {
        globalSwitch.onConfigChange(false);
        String result = testService.normalFlow();
        assertThat(result).isEqualTo("OK");
        assertThat(recordingHandler.beforeCount.get()).isZero();
    }

    @Test
    void around_maxNestingDepth_skipsWithoutMetricsError() {
        String result = testService.outerCall();
        assertThat(result).isEqualTo("outer-inner");
        assertThat(recordingHandler.beforeCount.get()).isEqualTo(1);
        assertThat(recordingHandler.afterCount.get()).isEqualTo(1);
    }

    @Test
    void around_handlerException_isolatedFromBusiness() {
        String result = testService.failingHandlerFlow();
        assertThat(result).isEqualTo("BIZ");
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {
        @Bean
        @Primary
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        FailingBeforeHandler failingBeforeHandler() {
            return new FailingBeforeHandler();
        }

        @Bean
        InnerService innerService() {
            return new InnerService();
        }

        @Bean
        TestService testService(InnerService innerService) {
            return new TestService(innerService);
        }
    }

    static class RecordingHandler implements LogCollectHandler {
        private final AtomicInteger beforeCount = new AtomicInteger(0);
        private final AtomicInteger afterCount = new AtomicInteger(0);

        @Override
        public void before(LogCollectContext context) {
            beforeCount.incrementAndGet();
        }

        @Override
        public void after(LogCollectContext context) {
            afterCount.incrementAndGet();
        }
    }

    static class FailingBeforeHandler implements LogCollectHandler {
        @Override
        public void before(LogCollectContext context) {
            throw new RuntimeException("handler failed");
        }
    }

    static class InnerService {
        @LogCollect(useBuffer = false, async = false)
        public String innerCall() {
            return "inner";
        }
    }

    static class TestService {
        private final InnerService innerService;

        TestService(InnerService innerService) {
            this.innerService = innerService;
        }

        @LogCollect(useBuffer = false, async = false)
        public String normalFlow() {
            return "OK";
        }

        @LogCollect(useBuffer = false, async = false)
        public String outerCall() {
            return "outer-" + innerService.innerCall();
        }

        @LogCollect(useBuffer = false, async = false, handler = FailingBeforeHandler.class)
        public String failingHandlerFlow() {
            return "BIZ";
        }
    }
}
