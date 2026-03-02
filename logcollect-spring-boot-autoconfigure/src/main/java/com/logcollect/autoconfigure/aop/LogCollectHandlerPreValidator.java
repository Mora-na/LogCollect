package com.logcollect.autoconfigure.aop;

import com.logcollect.api.annotation.LogCollect;
import com.logcollect.api.exception.LogCollectException;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 启动期校验 LogCollectHandler 装配，避免配置错误延迟到首次调用时才暴露。
 */
public class LogCollectHandlerPreValidator implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;

    public LogCollectHandlerPreValidator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, LogCollectHandler> handlers = applicationContext.getBeansOfType(LogCollectHandler.class);
        if (handlers.isEmpty()) {
            LogCollectInternalLogger.warn(
                    "[LogCollect] 未发现任何 LogCollectHandler 实现。"
                            + "所有 @LogCollect 方法将回退到 NoopLogCollectHandler（日志不会被持久化）。"
                            + "如果这不符合预期，请注册一个 LogCollectHandler Bean。");
            return;
        }
        if (handlers.size() <= 1) {
            return;
        }
        if (hasPrimaryHandler(handlers)) {
            return;
        }
        if (hasAutoHandlerMethod()) {
            throw new LogCollectException(
                    "发现多个 LogCollectHandler: " + handlers.keySet()
                            + "，请使用 @Primary 标注默认实现或在 @LogCollect 注解中显式指定 handler");
        }
    }

    private boolean hasPrimaryHandler(Map<String, LogCollectHandler> handlers) {
        for (String beanName : handlers.keySet()) {
            Primary primary = applicationContext.findAnnotationOnBean(beanName, Primary.class);
            if (primary != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAutoHandlerMethod() {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type = applicationContext.getType(beanName);
            if (type == null) {
                continue;
            }
            Method[] methods = ReflectionUtils.getAllDeclaredMethods(type);
            for (Method method : methods) {
                LogCollect annotation = AnnotationUtils.findAnnotation(method, LogCollect.class);
                if (annotation == null) {
                    continue;
                }
                if (annotation.handler() == LogCollectHandler.class) {
                    return true;
                }
            }
        }
        return false;
    }
}
