package com.logcollect.config.apollo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "com.ctrip.framework.apollo.ConfigService")
@ConditionalOnProperty(name = "logcollect.config.apollo.enabled", havingValue = "true")
public class LogCollectApolloAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApolloLogCollectConfigSource.class)
    public ApolloLogCollectConfigSource apolloLogCollectConfigSource() {
        return new ApolloLogCollectConfigSource();
    }
}
