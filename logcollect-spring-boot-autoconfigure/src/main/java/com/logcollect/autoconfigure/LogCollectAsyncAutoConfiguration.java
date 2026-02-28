package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.async.LogCollectAsyncConfigurer;
import com.logcollect.autoconfigure.async.LogCollectThreadPoolBPP;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@ConditionalOnClass(name = "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor")
public class LogCollectAsyncAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AsyncConfigurer.class)
    public LogCollectAsyncConfigurer logCollectAsyncConfigurer() {
        return new LogCollectAsyncConfigurer();
    }

    @Bean
    @ConditionalOnClass({ThreadPoolTaskExecutor.class, ThreadPoolTaskScheduler.class})
    public LogCollectThreadPoolBPP logCollectThreadPoolBPP() {
        return new LogCollectThreadPoolBPP();
    }
}
