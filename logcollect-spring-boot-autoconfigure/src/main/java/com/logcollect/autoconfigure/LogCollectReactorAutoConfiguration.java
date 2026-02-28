package com.logcollect.autoconfigure;

import com.logcollect.autoconfigure.reactive.ReactorContextPropagationConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "reactor.core.publisher.Hooks")
public class LogCollectReactorAutoConfiguration {
    @Bean
    public ReactorContextPropagationConfigurer reactorContextPropagationConfigurer() {
        return new ReactorContextPropagationConfigurer();
    }
}
