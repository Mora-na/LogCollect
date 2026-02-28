package com.logcollect.autoconfigure;

import com.logcollect.core.context.LogCollectThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "io.micrometer.context.ContextRegistry")
public class LogCollectContextPropagationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public LogCollectThreadLocalAccessor logCollectThreadLocalAccessor() {
        LogCollectThreadLocalAccessor accessor = new LogCollectThreadLocalAccessor();
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
        return accessor;
    }
}
