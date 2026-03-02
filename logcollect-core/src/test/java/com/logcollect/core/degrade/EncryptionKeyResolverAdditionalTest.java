package com.logcollect.core.degrade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.vault.core.VaultTemplate;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EncryptionKeyResolverAdditionalTest {

    @AfterEach
    void resetProvider() {
        TestKmsKeyProvider.reset();
    }

    @Test
    void resolveKey_prefersKmsProviderWhenAvailable() {
        byte[] kms = new byte[32];
        for (int i = 0; i < kms.length; i++) {
            kms[i] = (byte) (i + 7);
        }
        TestKmsKeyProvider.key = kms;

        byte[] resolved = EncryptionKeyResolver.resolveKey(null, new String[]{"prod"});
        assertThat(resolved).isEqualTo(kms);
    }

    @Test
    void resolveKey_kmsThrows_thenVaultPathReturnsKey() {
        TestKmsKeyProvider.throwError = true;

        byte[] vaultBytes = new byte[32];
        for (int i = 0; i < vaultBytes.length; i++) {
            vaultBytes[i] = (byte) (i + 11);
        }
        String base64 = Base64.getEncoder().encodeToString(vaultBytes);
        VaultTemplate template = new VaultTemplate(new VaultResponse(
                Collections.singletonMap("degrade-file-key", base64)));

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(any(Class.class))).thenReturn(template);
        Environment env = mock(Environment.class);
        when(context.getEnvironment()).thenReturn(env);
        when(env.getProperty("logcollect.global.degrade.file.encrypt-key")).thenReturn(null);

        byte[] resolved = EncryptionKeyResolver.resolveKey(context, new String[]{"prod"});
        assertThat(resolved).isEqualTo(vaultBytes);
    }

    @Test
    void resolveFromVault_privateMethod_branchesCovered() throws Exception {
        Method resolveFromVault = EncryptionKeyResolver.class
                .getDeclaredMethod("resolveFromVault", ApplicationContext.class);
        resolveFromVault.setAccessible(true);

        ApplicationContext noBean = mock(ApplicationContext.class);
        when(noBean.getBean(any(Class.class))).thenThrow(new RuntimeException("missing"));
        assertThat(resolveFromVault.invoke(null, noBean)).isNull();

        VaultTemplate nullResponseTemplate = new VaultTemplate(null);
        ApplicationContext nullResponseCtx = mock(ApplicationContext.class);
        when(nullResponseCtx.getBean(any(Class.class))).thenReturn(nullResponseTemplate);
        assertThat(resolveFromVault.invoke(null, nullResponseCtx)).isNull();

        VaultTemplate nonMapTemplate = new VaultTemplate(new NonMapResponse("text"));
        ApplicationContext nonMapCtx = mock(ApplicationContext.class);
        when(nonMapCtx.getBean(any(Class.class))).thenReturn(nonMapTemplate);
        assertThat(resolveFromVault.invoke(null, nonMapCtx)).isNull();

        VaultTemplate noKeyTemplate = new VaultTemplate(new VaultResponse(Collections.emptyMap()));
        ApplicationContext noKeyCtx = mock(ApplicationContext.class);
        when(noKeyCtx.getBean(any(Class.class))).thenReturn(noKeyTemplate);
        assertThat(resolveFromVault.invoke(null, noKeyCtx)).isNull();
    }

    @Test
    void isProductionEnvironment_privateMethod_branchesCovered() throws Exception {
        Method isProduction = EncryptionKeyResolver.class
                .getDeclaredMethod("isProductionEnvironment", String[].class);
        isProduction.setAccessible(true);

        boolean noProfiles = (Boolean) isProduction.invoke(null, (Object) null);
        assertThat(noProfiles).isTrue();

        boolean devOnly = (Boolean) isProduction.invoke(null, (Object) new String[]{null, "dev"});
        assertThat(devOnly).isFalse();

        boolean prodMixed = (Boolean) isProduction.invoke(null, (Object) new String[]{"dev", "production"});
        assertThat(prodMixed).isTrue();
    }

    @Test
    void resolveKey_prodRejectsConfigWhenNoOtherSource() {
        TestKmsKeyProvider.key = null;
        TestKmsKeyProvider.throwError = false;

        byte[] raw = new byte[32];
        String base64 = Base64.getEncoder().encodeToString(raw);
        ApplicationContext context = mock(ApplicationContext.class);
        Environment env = mock(Environment.class);
        when(context.getEnvironment()).thenReturn(env);
        when(context.getBean(any(Class.class))).thenThrow(new RuntimeException("no-vault"));
        when(env.getProperty("logcollect.global.degrade.file.encrypt-key")).thenReturn(base64);

        assertThatThrownBy(() -> EncryptionKeyResolver.resolveKey(context, new String[]{"prod"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No encryption key found");
    }

    public static final class VaultResponse {
        private final Map<String, Object> data;

        VaultResponse(Map<String, Object> data) {
            this.data = data;
        }

        public Map<String, Object> getData() {
            return data;
        }
    }

    public static final class NonMapResponse {
        private final Object data;

        NonMapResponse(Object data) {
            this.data = data;
        }

        public Object getData() {
            return data;
        }
    }
}
