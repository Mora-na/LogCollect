package com.logcollect.config.nacos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "com.alibaba.nacos.api.config.ConfigService")
@ConditionalOnProperty(name = "logcollect.config.nacos.enabled", havingValue = "true")
public class LogCollectNacosAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(NacosLogCollectConfigSource.class)
    public NacosLogCollectConfigSource nacosLogCollectConfigSource() {
        return new NacosLogCollectConfigSource();
    }
}
