package com.logcollect.autoconfigure.aop;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.autoconfigure.LogCollectAopAutoConfiguration;
import com.logcollect.autoconfigure.LogCollectAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {
                LogCollectAutoConfiguration.class,
                LogCollectAopAutoConfiguration.class,
                LogCollectAspectNoHandlerIntegrationTest.TestConfig.class
        },
        properties = {
                "logcollect.global.enabled=true",
                "logcollect.global.async=false",
                "logcollect.global.buffer.enabled=false"
        }
)
class LogCollectAspectNoHandlerIntegrationTest {

    @Autowired
    private NoHandlerService noHandlerService;

    @Test
    void around_noHandler_fallsBackToNoop() {
        assertThat(noHandlerService.execute()).isEqualTo("NOOP");
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {
        @Bean
        NoHandlerService noHandlerService() {
            return new NoHandlerService();
        }
    }

    static class NoHandlerService {
        @LogCollect(useBuffer = false, async = false)
        public String execute() {
            return "NOOP";
        }
    }
}
