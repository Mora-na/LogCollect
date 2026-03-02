package com.logcollect.autoconfigure.aop;

import com.logcollect.core.context.LogCollectContextManager;
import com.logcollect.core.context.LogCollectIgnoreManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * {@code @LogCollectIgnore} 切面。
 */
@Aspect
public class LogCollectIgnoreAspect {

    @Around("@annotation(com.logcollect.api.annotation.LogCollectIgnore) || @within(com.logcollect.api.annotation.LogCollectIgnore)")
    public Object aroundIgnored(ProceedingJoinPoint pjp) throws Throwable {
        if (LogCollectContextManager.current() == null) {
            return pjp.proceed();
        }
        LogCollectIgnoreManager.enter();
        try {
            return pjp.proceed();
        } finally {
            LogCollectIgnoreManager.exit();
        }
    }
}
