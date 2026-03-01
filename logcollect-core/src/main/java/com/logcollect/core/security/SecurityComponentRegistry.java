package com.logcollect.core.security;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.sanitizer.LogSanitizer;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ConcurrentHashMap;

public class SecurityComponentRegistry {

    private final ApplicationContext applicationContext;
    private final ConcurrentHashMap<Class<?>, Object> sanitizerCache = new ConcurrentHashMap<Class<?>, Object>();
    private final ConcurrentHashMap<Class<?>, Object> maskerCache = new ConcurrentHashMap<Class<?>, Object>();

    public SecurityComponentRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public LogSanitizer getSanitizer(LogCollectConfig config) {
        if (config == null || !config.isEnableSanitize()) {
            return NoOpLogSanitizer.INSTANCE;
        }
        Class<? extends LogSanitizer> clazz = config.getSanitizerClass();
        if (clazz == null || clazz == LogSanitizer.class) {
            if (applicationContext != null) {
                try {
                    return applicationContext.getBean(LogSanitizer.class);
                } catch (NoSuchBeanDefinitionException ignored) {
                }
            }
            return new DefaultLogSanitizer();
        }
        return (LogSanitizer) sanitizerCache.computeIfAbsent(clazz, this::instantiate);
    }

    public LogMasker getMasker(LogCollectConfig config) {
        if (config == null || !config.isEnableMask()) {
            return NoOpLogMasker.INSTANCE;
        }
        Class<? extends LogMasker> clazz = config.getMaskerClass();
        if (clazz == null || clazz == LogMasker.class) {
            if (applicationContext != null) {
                try {
                    return applicationContext.getBean(LogMasker.class);
                } catch (NoSuchBeanDefinitionException ignored) {
                }
            }
            return new DefaultLogMasker();
        }
        return (LogMasker) maskerCache.computeIfAbsent(clazz, this::instantiate);
    }

    private Object instantiate(Class<?> clazz) {
        if (applicationContext != null) {
            try {
                return applicationContext.getBean(clazz);
            } catch (NoSuchBeanDefinitionException ignored) {
            }
        }
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot instantiate " + clazz.getName(), ex);
        }
    }
}
