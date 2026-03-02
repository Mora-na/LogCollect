package com.logcollect.core.degrade;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;

/**
 * 降级文件 AES-GCM 加解密器。
 */
public class DegradeFileEncryptor {
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * 创建加解密器。
     *
     * @param key AES 密钥
     */
    public DegradeFileEncryptor(SecretKey key) {
        this.key = key;
    }

    /**
     * 加密明文。
     *
     * @param plaintext 明文字节数组
     * @return 密文字节数组（前缀包含 IV）
     * @throws DegradeStorageException 加密失败时抛出
     */
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (Throwable t) {
            throw new DegradeStorageException(t);
        }
    }

    /**
     * 解密密文。
     *
     * @param ciphertext 密文字节数组（前缀需包含 IV）
     * @return 解密后的明文字节数组
     * @throws DegradeStorageException 解密失败时抛出
     */
    public byte[] decrypt(byte[] ciphertext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(ciphertext, 0, iv, 0, IV_LENGTH);
            byte[] body = new byte[ciphertext.length - IV_LENGTH];
            System.arraycopy(ciphertext, IV_LENGTH, body, 0, body.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            return cipher.doFinal(body);
        } catch (Throwable t) {
            throw new DegradeStorageException(t);
        }
    }
}
