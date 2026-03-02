package com.logcollect.api.annotation;

import java.lang.annotation.*;

/**
 * 反向排除注解。
 *
 * <p>标注在方法或类上，表示该范围内日志不参与当前 {@link LogCollect}
 * 收集流程。常用于在被收集方法内部调用不希望被采集的辅助逻辑。</p>
 *
 * @since 1.0.0
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogCollectIgnore {
}
