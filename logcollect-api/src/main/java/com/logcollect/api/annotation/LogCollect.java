package com.logcollect.api.annotation;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.handler.LogCollectHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogCollect {
    Class<? extends LogCollectHandler> handler() default LogCollectHandler.class;
    boolean async() default true;
    String level() default "INFO";
    boolean useBuffer() default true;
    int maxBufferSize() default 100;
    long maxBufferBytes() default 1048576L;
    int degradeFileTTLDays() default 90;

    CollectMode collectMode() default CollectMode.AUTO;

    int handlerTimeoutMs() default 5000;
    boolean transactionIsolation() default false;
    int maxNestingDepth() default 10;
}
