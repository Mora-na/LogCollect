package com.logcollect.api.enums;

public enum DegradeStorage {
    /**
     * 降级到本地文件（默认，可选加密）。
     */
    FILE,

    /**
     * 降级到固定大小内存队列（JVM 重启丢失）。
     */
    LIMITED_MEMORY,

    /**
     * 仅保留 ERROR/FATAL 级别，其它级别丢弃。
     */
    DISCARD_NON_ERROR,

    /**
     * 丢弃全部日志，零 IO 开销，仅记指标。
     */
    DISCARD_ALL
}
