package com.logcollect.autoconfigure.async;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Spring {@code @Async} 默认执行器配置。
 *
 * <p>当业务未自定义 {@link AsyncConfigurer} 时，框架提供一个带
 * {@link LogCollectTaskDecorator} 的默认线程池，确保 @Async 任务可自动传播上下文。
 */
@Configuration
public class LogCollectAsyncConfigurer implements AsyncConfigurer {
    /**
     * 创建默认异步执行器。
     *
     * @return 默认线程池执行器
     */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        TaskDecorator decorator = new LogCollectTaskDecorator();
        executor.setTaskDecorator(decorator);
        executor.initialize();
        return executor;
    }
}
