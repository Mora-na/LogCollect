package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.aop.LogCollectAspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
public class LogCollectAopAutoConfiguration {
    @Bean
    public LogCollectAspect logCollectAspect() {
        return new LogCollectAspect();
    }
}
