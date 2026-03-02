package com.logcollect.api.enums;

/**
 * 日志收集模式枚举。
 *
 * <p>决定日志条目在缓冲和回调 Handler 时的组织方式。</p>
 *
 * @since 1.0.0
 */
public enum CollectMode {
    /** 自动选择模式，最终解析为 {@link #AGGREGATE}。 */
    AUTO,
    /** 单条模式：缓冲后逐条调用 Handler。 */
    SINGLE,
    /** 聚合模式：缓冲后聚合拼接，一次调用 Handler。 */
    AGGREGATE;

    public CollectMode resolve() {
        return this == AUTO ? AGGREGATE : this;
    }
}
