package com.logcollect.autoconfigure.management;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 无 Spring Security 时拦截管理端写操作。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnMissingClass("org.springframework.security.web.SecurityFilterChain")
@ConditionalOnProperty(prefix = "logcollect.management", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogCollectActuatorWriteProtectionAutoConfiguration {

    @Bean
    @ConditionalOnClass(name = "javax.servlet.Filter")
    @ConditionalOnMissingClass("jakarta.servlet.Filter")
    public Object javaxLogCollectWriteProtectionFilter(
            @Autowired(required = false) LogCollectManagementAuditLogger auditLogger) {
        try {
            Class<?> registrationClass = Class.forName("org.springframework.boot.web.servlet.FilterRegistrationBean");
            Class<?> javaxFilterClass = Class.forName("javax.servlet.Filter");

            Object registration = registrationClass.newInstance();
            Object filter = new JavaxLogCollectWriteProtectionFilter(auditLogger);

            registrationClass.getMethod("setFilter", javaxFilterClass).invoke(registration, filter);
            registrationClass.getMethod("addUrlPatterns", String[].class)
                    .invoke(registration, new Object[]{new String[]{"/*"}});
            registrationClass.getMethod("setOrder", int.class)
                    .invoke(registration, Ordered.HIGHEST_PRECEDENCE + 20);
            return registration;
        } catch (Exception e) {
            throw new IllegalStateException("Register javax write-protection filter failed", e);
        }
    }

    @Bean
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    public Object jakartaLogCollectWriteProtectionFilter(
            @Autowired(required = false) LogCollectManagementAuditLogger auditLogger) {
        try {
            Class<?> registrationClass = Class.forName("org.springframework.boot.web.servlet.FilterRegistrationBean");
            Class<?> jakartaFilterClass = Class.forName("jakarta.servlet.Filter");

            Object registration = registrationClass.newInstance();
            Object filter = new JakartaLogCollectWriteProtectionFilter(auditLogger);

            registrationClass.getMethod("setFilter", jakartaFilterClass).invoke(registration, filter);
            registrationClass.getMethod("addUrlPatterns", String[].class)
                    .invoke(registration, new Object[]{new String[]{"/*"}});
            registrationClass.getMethod("setOrder", int.class)
                    .invoke(registration, Ordered.HIGHEST_PRECEDENCE + 20);
            return registration;
        } catch (Exception e) {
            throw new IllegalStateException("Register jakarta write-protection filter failed", e);
        }
    }
}
