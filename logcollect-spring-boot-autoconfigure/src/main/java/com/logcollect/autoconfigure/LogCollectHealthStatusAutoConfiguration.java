package com.logcollect.autoconfigure;

import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HttpCodeStatusMapper")
public class LogCollectHealthStatusAutoConfiguration {

    @Bean
    public HttpCodeStatusMapper logCollectHttpCodeStatusMapper() {
        Map<String, Integer> statusMapping = new LinkedHashMap<String, Integer>();
        statusMapping.put("DOWN", 503);
        statusMapping.put("OUT_OF_SERVICE", 503);
        statusMapping.put("UP", 200);
        statusMapping.put("UNKNOWN", 200);
        statusMapping.put("DEGRADED", 200);
        return new SimpleHttpCodeStatusMapper(statusMapping);
    }
}
