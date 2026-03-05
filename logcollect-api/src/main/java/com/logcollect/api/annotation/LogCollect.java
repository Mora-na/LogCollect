package com.logcollect.api.annotation;

import com.logcollect.api.backpressure.BackpressureCallback;
import com.logcollect.api.enums.CollectMode;
import com.logcollect.api.enums.DegradeStorage;
import com.logcollect.api.enums.SamplingStrategy;
import com.logcollect.api.enums.TotalLimitPolicy;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.sanitizer.LogSanitizer;

import java.lang.annotation.*;

/**
 * 标记需要进行业务日志聚合收集的方法。
 *
 * <p>框架会在标注方法执行期间自动拦截指定级别日志，经过安全流水线
 * （净化 + 脱敏）后，按收集模式缓冲并回调 {@link LogCollectHandler}
 * 完成持久化。</p>
 *
 * <p>支持同步与异步场景的上下文传播，包括 {@code @Async}、线程池、
 * WebFlux 响应式链路等。</p>
 *
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogCollect {

    // ===== 基础配置 =====
    Class<? extends LogCollectHandler> handler() default LogCollectHandler.class;
    boolean async() default true;
    String minLevel() default "";
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
    int pipelineTimeoutMs() default 50;
    int pipelineRingBufferCapacity() default -1;
    /**
     * @deprecated replaced by pipelineRingBufferCapacity in v2.1.
     */
    @Deprecated
    int pipelineQueueCapacity() default 8192;

    // ===== 高级配置 =====
    int handlerTimeoutMs() default 5000;
    boolean transactionIsolation() default false;
    int maxNestingDepth() default 10;
    int maxTotalCollect() default 100000;
    String maxTotalCollectBytes() default "50MB";
    TotalLimitPolicy totalLimitPolicy() default TotalLimitPolicy.STOP_COLLECTING;
    double samplingRate() default 1.0d;
    SamplingStrategy samplingStrategy() default SamplingStrategy.RATE;
    Class<? extends BackpressureCallback> backpressure() default BackpressureCallback.class;

    // ===== 可观测性配置 =====
    boolean enableMetrics() default true;
}
