package com.logcollect.core.degrade;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EncryptionKeyResolverTest {

    @Test
    void resolveKey_noSource_throwsIllegalState() {
        assertThatThrownBy(() -> EncryptionKeyResolver.resolveKey(null, new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No encryption key found");
    }

    @Test
    void resolveKey_fromConfigInDevProfile_returnsDecodedKey() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i + 3);
        }
        String base64 = Base64.getEncoder().encodeToString(raw);

        ApplicationContext context = mock(ApplicationContext.class);
        Environment environment = mock(Environment.class);
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("logcollect.global.degrade.file.encrypt-key")).thenReturn(base64);

        byte[] resolved = EncryptionKeyResolver.resolveKey(context, new String[]{"dev"});
        assertThat(resolved).isEqualTo(raw);
    }

    @Test
    void resolveKey_configKeyRejectedInProd_thenThrows() {
        byte[] raw = new byte[32];
        String base64 = Base64.getEncoder().encodeToString(raw);

        ApplicationContext context = mock(ApplicationContext.class);
        Environment environment = mock(Environment.class);
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("logcollect.global.degrade.file.encrypt-key")).thenReturn(base64);

        assertThatThrownBy(() -> EncryptionKeyResolver.resolveKey(context, new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No encryption key found");
    }

    @Test
    void resolveKey_invalidConfigBase64_throws() {
        ApplicationContext context = mock(ApplicationContext.class);
        Environment environment = mock(Environment.class);
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("logcollect.global.degrade.file.encrypt-key")).thenReturn("%%%invalid%%%");

        assertThatThrownBy(() -> EncryptionKeyResolver.resolveKey(context, new String[]{"dev"}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
