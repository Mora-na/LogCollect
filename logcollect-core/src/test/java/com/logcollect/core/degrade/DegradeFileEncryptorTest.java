package com.logcollect.core.degrade;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DegradeFileEncryptorTest {

    @Test
    void encrypt_withNullKey_throwsDegradeStorageException() {
        DegradeFileEncryptor encryptor = new DegradeFileEncryptor(null);
        assertThatThrownBy(() -> encryptor.encrypt(new byte[]{1, 2, 3}))
                .isInstanceOf(DegradeStorageException.class);
    }

    @Test
    void decrypt_withShortCiphertext_throwsDegradeStorageException() {
        SecretKey key = new SecretKeySpec(new byte[32], "AES");
        DegradeFileEncryptor encryptor = new DegradeFileEncryptor(key);
        assertThatThrownBy(() -> encryptor.decrypt(new byte[]{1}))
                .isInstanceOf(DegradeStorageException.class);
    }
}
