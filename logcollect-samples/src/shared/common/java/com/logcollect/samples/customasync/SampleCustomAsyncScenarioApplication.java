package com.logcollect.samples.customasync;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.samples.shared.SampleScenarioLogCollectHandler;
import com.logcollect.samples.shared.SampleScenarioSupport;
import com.logcollect.autoconfigure.async.LogCollectTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableAsync
@ComponentScan(basePackages = {"com.logcollect.samples.customasync", "com.logcollect.samples.shared"})
public class SampleCustomAsyncScenarioApplication {

    @Bean(name = "sampleCustomAsyncScenarioRunner")
    Runnable sampleCustomAsyncScenarioRunner(final SampleCustomAsyncScenarioService service) {
        return new Runnable() {
            @Override
            public void run() {
                service.runScenario();
            }
        };
    }
}

@Configuration
class SampleCustomAsyncScenarioConfiguration implements AsyncConfigurer {

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor customAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("sample-custom-async-");
        executor.setTaskDecorator(new LogCollectTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return customAsyncExecutor();
    }
}

@Service
class SampleCustomAsyncScenarioService {
    private static final Logger log = LoggerFactory.getLogger(SampleCustomAsyncScenarioService.class);

    private final SampleCustomAsyncScenarioWorker worker;

    SampleCustomAsyncScenarioService(SampleCustomAsyncScenarioWorker worker) {
        this.worker = worker;
    }

    @LogCollect(
            handler = SampleScenarioLogCollectHandler.class,
            async = false,
            collectMode = CollectMode.AGGREGATE,
            maxBufferSize = 512,
            maxBufferBytes = "4MB"
    )
    public void runScenario() {
        String code = "03";
        String title = "Spring@Async-自定义 AsyncConfigurer";
        SampleScenarioSupport.enterScenario(log, code, title, 4);
        SampleScenarioSupport.step(log, code, title, "01", "通过独立子上下文验证自定义 AsyncConfigurer");
        String result = SampleScenarioSupport.get(worker.runFragment(code, title, "02"), title);
        SampleScenarioSupport.step(log, code, title, "03", "自定义 AsyncConfigurer 任务返回={}", result);
    }
}

@Service
class SampleCustomAsyncScenarioWorker {
    private static final Logger log = LoggerFactory.getLogger(SampleCustomAsyncScenarioWorker.class);

    @Async
    public CompletableFuture<String> runFragment(String code, String title, String step) {
        SampleScenarioSupport.step(log, code, title, step, "自定义 AsyncConfigurer 线程={} 正在执行",
                Thread.currentThread().getName());
        return CompletableFuture.completedFuture("custom-async-ok");
    }
}
