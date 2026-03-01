package com.logcollect.config.springcloud;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "org.springframework.cloud.context.environment.EnvironmentChangeEvent")
@ConditionalOnProperty(name = "logcollect.config.spring-cloud.enabled", havingValue = "true")
public class LogCollectSpringCloudAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SpringCloudConfigSource.class)
    public SpringCloudConfigSource springCloudConfigSource() {
        return new SpringCloudConfigSource();
    }
}
