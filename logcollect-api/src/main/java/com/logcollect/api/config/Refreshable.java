package com.logcollect.api.config;

/**
 * 可刷新组件标记接口。
 *
 * <p>实现类可在配置变更或外部事件触发时重新加载内部状态。
 */
public interface Refreshable {
    void refresh();
}
