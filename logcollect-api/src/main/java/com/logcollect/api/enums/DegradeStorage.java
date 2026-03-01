package com.logcollect.api.enums;

public enum DegradeStorage {
    /**
     * 降级到本地文件。
     */
    FILE,

    /**
     * 降级到固定大小内存队列。
     */
    LIMITED_MEMORY,

    /**
     * 仅保留 ERROR/FATAL 级别，其它级别丢弃。
     */
    DISCARD_NON_ERROR,

    /**
     * 丢弃全部日志（终极保护）。
     */
    DISCARD_ALL
}
