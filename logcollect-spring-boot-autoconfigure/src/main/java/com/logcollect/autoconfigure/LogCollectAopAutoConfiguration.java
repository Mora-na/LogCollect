package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.aop.LogCollectAspect;
import com.logcollect.autoconfigure.aop.LogCollectHandlerPreValidator;
import com.logcollect.autoconfigure.aop.LogCollectIgnoreAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
public class LogCollectAopAutoConfiguration {
    @Bean
    public LogCollectAspect logCollectAspect() {
        return new LogCollectAspect();
    }

    @Bean
    public LogCollectHandlerPreValidator logCollectHandlerPreValidator(ApplicationContext applicationContext) {
        return new LogCollectHandlerPreValidator(applicationContext);
    }

    @Bean
    public LogCollectIgnoreAspect logCollectIgnoreAspect() {
        return new LogCollectIgnoreAspect();
    }
}
