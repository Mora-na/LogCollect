package com.logcollect.autoconfigure.management;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@ConditionalOnClass({SecurityFilterChain.class, HttpSecurity.class})
@ConditionalOnProperty(prefix = "logcollect.management.security", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogCollectActuatorSecurityAutoConfiguration {

    @Bean(name = "logCollectActuatorSecurityFilterChain")
    @Order(10)
    @ConditionalOnMissingBean(name = "logCollectActuatorSecurityFilterChain")
    public SecurityFilterChain logCollectActuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        AntPathRequestMatcher matcher = new AntPathRequestMatcher("/actuator/logcollect/**");
        http.requestMatcher(matcher)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(new AntPathRequestMatcher("/actuator/logcollect/**", "GET"))
                        .hasAnyRole("MONITOR", "ADMIN")
                        .requestMatchers(new AntPathRequestMatcher("/actuator/logcollect/**", "POST"))
                        .hasRole("ADMIN")
                        .requestMatchers(new AntPathRequestMatcher("/actuator/logcollect/**", "PUT"))
                        .hasRole("ADMIN")
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
