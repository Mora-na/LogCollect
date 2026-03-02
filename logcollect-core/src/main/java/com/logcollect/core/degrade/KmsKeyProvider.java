package com.logcollect.core.degrade;

/**
 * 外部 KMS 密钥提供者 SPI。
 */
public interface KmsKeyProvider {
    /**
     * 根据别名查询密钥。
     *
     * @param keyAlias 密钥别名
     * @return 密钥字节；未命中时返回 null
     */
    byte[] getKey(String keyAlias);
}
