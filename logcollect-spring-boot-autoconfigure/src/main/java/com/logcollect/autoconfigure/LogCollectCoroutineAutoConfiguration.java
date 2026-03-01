package com.logcollect.autoconfigure;

import com.logcollect.core.context.LogCollectCoroutineContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
@ConditionalOnClass(name = "kotlinx.coroutines.ThreadContextElement")
public class LogCollectCoroutineAutoConfiguration {

    @Bean
    @Scope("prototype")
    public LogCollectCoroutineContext logCollectCoroutineContext() {
        return new LogCollectCoroutineContext();
    }
}
