package com.logcollect.core.degrade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class DegradeKeyManagerTest {

    private static final String PROP_KEY = "logcollect.degrade.file.encrypt-key";
    private String originalValue;

    @BeforeEach
    void setUp() {
        originalValue = System.getProperty(PROP_KEY);
        TestDegradeKeyProvider.reset();
    }

    @AfterEach
    void tearDown() {
        if (originalValue == null) {
            System.clearProperty(PROP_KEY);
        } else {
            System.setProperty(PROP_KEY, originalValue);
        }
        TestDegradeKeyProvider.reset();
    }

    @Test
    void resolveKey_withoutAnySource_returnsNull() {
        System.clearProperty(PROP_KEY);
        SecretKey key = new DegradeKeyManager().resolveKey();
        assertThat(key).isNull();
    }

    @Test
    void resolveKey_withValidSystemProperty_returnsAesKey() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        System.setProperty(PROP_KEY, Base64.getEncoder().encodeToString(bytes));

        SecretKey key = new DegradeKeyManager().resolveKey();
        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("AES");
        assertThat(key.getEncoded()).hasSize(32);
    }

    @Test
    void resolveKey_withInvalidLength_returnsNull() {
        byte[] bytes = new byte[16];
        System.setProperty(PROP_KEY, Base64.getEncoder().encodeToString(bytes));

        SecretKey key = new DegradeKeyManager().resolveKey();
        assertThat(key).isNull();
    }

    @Test
    void resolveKey_withInvalidBase64_returnsNull() {
        System.setProperty(PROP_KEY, "%%%invalid%%%");
        SecretKey key = new DegradeKeyManager().resolveKey();
        assertThat(key).isNull();
    }

    @Test
    void resolveKey_withProviderKey_returnsProviderResult() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 9);
        }
        SecretKey providerKey = new SecretKeySpec(bytes, "AES");
        TestDegradeKeyProvider.key = providerKey;
        TestDegradeKeyProvider.order = 1;

        SecretKey key = new DegradeKeyManager().resolveKey();
        assertThat(key).isNotNull();
        assertThat(key.getEncoded()).isEqualTo(bytes);
    }

    @Test
    void resolveKey_whenProviderThrows_fallsBackToNull() {
        TestDegradeKeyProvider.throwError = true;
        System.clearProperty(PROP_KEY);
        SecretKey key = new DegradeKeyManager().resolveKey();
        assertThat(key).isNull();
    }
}
