package com.logcollect.core.degrade;

import javax.crypto.SecretKey;

/**
 * 降级文件加密密钥提供者 SPI。
 */
public interface DegradeKeyProvider {
    /**
     * 解析密钥。
     *
     * @return 可用密钥；不可用时返回 null
     */
    SecretKey resolve();

    /**
     * Provider 顺序，值越小优先级越高。
     *
     * @return 优先级顺序值
     */
    int getOrder();
}
