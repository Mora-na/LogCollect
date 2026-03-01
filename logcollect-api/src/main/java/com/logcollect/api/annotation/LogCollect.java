package com.logcollect.api.annotation;

import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.sanitizer.LogSanitizer;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogCollect {

    // ===== 基础配置 =====
    Class<? extends LogCollectHandler> handler() default LogCollectHandler.class;
    boolean async() default true;
    String minLevel() default "TRACE";
    String[] excludeLoggers() default {};
    CollectMode collectMode() default CollectMode.AUTO;

    // ===== 缓冲区配置 =====
    boolean useBuffer() default true;
    int maxBufferSize() default 100;
    String maxBufferBytes() default "1MB";

    // ===== 熔断降级配置 =====
    boolean enableDegrade() default true;
    int degradeFailThreshold() default 5;
    DegradeStorage degradeStorage() default DegradeStorage.FILE;
    int recoverIntervalSeconds() default 30;
    int recoverMaxIntervalSeconds() default 300;
    int halfOpenPassCount() default 3;
    int halfOpenSuccessThreshold() default 3;
    boolean blockWhenDegradeFail() default false;

    // ===== 安全防护配置 =====
    boolean enableSanitize() default true;
    Class<? extends LogSanitizer> sanitizer() default LogSanitizer.class;
    boolean enableMask() default true;
    Class<? extends LogMasker> masker() default LogMasker.class;

    // ===== 高级配置 =====
    int handlerTimeoutMs() default 5000;
    boolean transactionIsolation() default false;
    int maxNestingDepth() default 10;

    // ===== 可观测性配置 =====
    boolean enableMetrics() default true;
}
