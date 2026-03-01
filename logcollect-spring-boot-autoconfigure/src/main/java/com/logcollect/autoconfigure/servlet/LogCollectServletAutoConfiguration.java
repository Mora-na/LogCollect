package com.logcollect.autoconfigure.servlet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LogCollectServletAutoConfiguration {

    @Bean
    @ConditionalOnClass(name = "javax.servlet.AsyncContext")
    @ConditionalOnMissingClass("jakarta.servlet.AsyncContext")
    public FilterRegistrationBean<JavaxLogCollectAsyncContextFilter> javaxLogCollectAsyncContextFilterRegistration() {
        FilterRegistrationBean<JavaxLogCollectAsyncContextFilter> registration =
                new FilterRegistrationBean<JavaxLogCollectAsyncContextFilter>();
        registration.setFilter(new JavaxLogCollectAsyncContextFilter());
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    @ConditionalOnClass(name = "jakarta.servlet.AsyncContext")
    public Object jakartaLogCollectAsyncContextFilterRegistration() {
        try {
            Class<?> registrationClass = Class.forName("org.springframework.boot.web.servlet.FilterRegistrationBean");
            Class<?> jakartaFilterClass = Class.forName("jakarta.servlet.Filter");

            Object registration = registrationClass.newInstance();
            Object filter = new JakartaLogCollectAsyncContextFilter();

            registrationClass.getMethod("setFilter", jakartaFilterClass).invoke(registration, filter);
            registrationClass.getMethod("setAsyncSupported", boolean.class).invoke(registration, true);
            registrationClass.getMethod("addUrlPatterns", String[].class)
                    .invoke(registration, new Object[]{new String[]{"/*"}});
            registrationClass.getMethod("setOrder", int.class)
                    .invoke(registration, Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        } catch (Throwable t) {
            throw new IllegalStateException("Register jakarta AsyncContext filter failed", t);
        }
    }
}
