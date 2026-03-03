package com.logcollect.core.degrade;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void encryptAndDecrypt_withValidKey_roundTripSucceeds() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        DegradeFileEncryptor encryptor = new DegradeFileEncryptor(key);
        byte[] plaintext = "degrade-file-payload".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = encryptor.encrypt(plaintext);
        byte[] decrypted = encryptor.decrypt(ciphertext);

        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(ciphertext.length).isGreaterThan(plaintext.length);
        assertThat(decrypted).isEqualTo(plaintext);
    }
}
