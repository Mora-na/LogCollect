package com.logcollect.autoconfigure.async;

import com.logcollect.api.model.LogCollectContext;
import com.logcollect.autoconfigure.LogCollectAsyncAutoConfiguration;
import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.context.LogCollectWrappedExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectAsyncScenarioCoverageTest {

    @AfterEach
    void tearDown() {
        LogCollectContextManager.clear();
    }

    @Test
    void asyncDefaultConfigurer_shouldPropagateContext() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(LogCollectAsyncAutoConfiguration.class, DefaultAsyncScenarioConfig.class);
            context.refresh();

            DefaultAsyncScenarioService service = context.getBean(DefaultAsyncScenarioService.class);
            String traceId = "trace-default-async";
            LogCollectContextManager.push(newContext(traceId));
            try {
                String asyncTraceId = service.readCurrentTraceId().get(3, TimeUnit.SECONDS);
                assertThat(asyncTraceId).isEqualTo(traceId);
            } finally {
                LogCollectContextManager.clear();
            }
        }
    }

    @Test
    void asyncCustomConfigurerWithTaskDecorator_shouldPropagateContext() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(LogCollectAsyncAutoConfiguration.class, CustomAsyncScenarioConfig.class);
            context.refresh();

            CustomAsyncScenarioService service = context.getBean(CustomAsyncScenarioService.class);
            String traceId = "trace-custom-async";
            LogCollectContextManager.push(newContext(traceId));
            try {
                String asyncTraceId = service.readCurrentTraceId().get(3, TimeUnit.SECONDS);
                assertThat(asyncTraceId).isEqualTo(traceId);
            } finally {
                LogCollectContextManager.clear();
            }
        }
    }

    @Test
    void completableFutureWithSpringManagedExecutorService_shouldPropagateContext() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(LogCollectAsyncAutoConfiguration.class, CompletableFutureScenarioConfig.class);
            context.refresh();

            ExecutorService springExecutor = context.getBean("springExecutorService", ExecutorService.class);
            assertThat(springExecutor).isInstanceOf(LogCollectWrappedExecutor.class);

            CompletableFutureScenarioService service = context.getBean(CompletableFutureScenarioService.class);
            String traceId = "trace-completablefuture-spring-pool";
            LogCollectContextManager.push(newContext(traceId));
            try {
                String asyncTraceId = service.readCurrentTraceId().get(3, TimeUnit.SECONDS);
                assertThat(asyncTraceId).isEqualTo(traceId);
            } finally {
                LogCollectContextManager.clear();
            }
        }
    }

    private static LogCollectContext newContext(String traceId) {
        return new LogCollectContext(traceId, null, null, null, null, null, null, null);
    }

    @Configuration
    @EnableAsync
    static class DefaultAsyncScenarioConfig {
        @Bean
        DefaultAsyncScenarioService defaultAsyncScenarioService() {
            return new DefaultAsyncScenarioService();
        }
    }

    static class DefaultAsyncScenarioService {
        @Async
        public CompletableFuture<String> readCurrentTraceId() {
            LogCollectContext current = LogCollectContextManager.current();
            return CompletableFuture.completedFuture(current == null ? null : current.getTraceId());
        }
    }

    @Configuration
    @EnableAsync
    static class CustomAsyncScenarioConfig implements AsyncConfigurer {
        @Bean(destroyMethod = "shutdown")
        ThreadPoolTaskExecutor customAsyncExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(16);
            executor.setThreadNamePrefix("custom-async-");
            executor.setTaskDecorator(new LogCollectTaskDecorator());
            executor.initialize();
            return executor;
        }

        @Override
        public Executor getAsyncExecutor() {
            return customAsyncExecutor();
        }

        @Bean
        CustomAsyncScenarioService customAsyncScenarioService() {
            return new CustomAsyncScenarioService();
        }
    }

    static class CustomAsyncScenarioService {
        @Async
        public CompletableFuture<String> readCurrentTraceId() {
            LogCollectContext current = LogCollectContextManager.current();
            return CompletableFuture.completedFuture(current == null ? null : current.getTraceId());
        }
    }

    @Configuration
    static class CompletableFutureScenarioConfig {
        @Bean(destroyMethod = "shutdownNow")
        ExecutorService springExecutorService() {
            return Executors.newFixedThreadPool(1);
        }

        @Bean
        CompletableFutureScenarioService completableFutureScenarioService(ExecutorService springExecutorService) {
            return new CompletableFutureScenarioService(springExecutorService);
        }
    }

    static class CompletableFutureScenarioService {
        private final Executor executor;

        CompletableFutureScenarioService(Executor executor) {
            this.executor = executor;
        }

        CompletableFuture<String> readCurrentTraceId() {
            return CompletableFuture.supplyAsync(() -> {
                LogCollectContext current = LogCollectContextManager.current();
                return current == null ? null : current.getTraceId();
            }, executor);
        }
    }
}
