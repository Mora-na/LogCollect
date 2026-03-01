package com.logcollect.autoconfigure.servlet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@ConditionalOnClass(name = "javax.servlet.AsyncContext")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LogCollectServletAutoConfiguration {

    @Bean
    public FilterRegistrationBean<LogCollectAsyncContextFilter> logCollectAsyncContextFilterRegistration() {
        FilterRegistrationBean<LogCollectAsyncContextFilter> registration =
                new FilterRegistrationBean<LogCollectAsyncContextFilter>();
        registration.setFilter(new LogCollectAsyncContextFilter());
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
