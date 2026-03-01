package com.logcollect.core.degrade;

import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.context.ApplicationContext;

import java.util.Base64;
import java.util.Map;
import java.util.ServiceLoader;

public final class EncryptionKeyResolver {

    private static final String ENV_KEY = "LOGCOLLECT_DEGRADE_FILE_KEY";
    private static final String SYS_PROP_KEY = "logcollect.degrade.file.encrypt-key";

    private EncryptionKeyResolver() {
    }

    public static byte[] resolveKey(ApplicationContext applicationContext, String profile) {
        for (KmsKeyProvider provider : ServiceLoader.load(KmsKeyProvider.class)) {
            try {
                byte[] key = provider.getKey("logcollect-degrade-file");
                if (key != null && key.length > 0) {
                    LogCollectInternalLogger.info("Encryption key loaded from KMS provider: {}",
                            provider.getClass().getSimpleName());
                    return key;
                }
            } catch (Throwable ignored) {
            }
        }

        String envKey = System.getenv(ENV_KEY);
        if (envKey != null && !envKey.isEmpty()) {
            return Base64.getDecoder().decode(envKey);
        }

        if (applicationContext != null) {
            byte[] vaultKey = resolveFromVault(applicationContext);
            if (vaultKey != null) {
                return vaultKey;
            }
        }

        String configKey = System.getProperty(SYS_PROP_KEY);
        if ((configKey == null || configKey.isEmpty()) && applicationContext != null) {
            configKey = applicationContext.getEnvironment().getProperty("logcollect.global.degrade.file.encrypt-key");
        }
        if (configKey != null && !configKey.isEmpty()) {
            if (isProductionProfile(profile)) {
                LogCollectInternalLogger.warn("Encryption key loaded from config file in production environment");
            }
            return Base64.getDecoder().decode(configKey);
        }

        throw new IllegalStateException("No encryption key found. Provide via KMS/env/Vault/config");
    }

    private static byte[] resolveFromVault(ApplicationContext applicationContext) {
        try {
            Class<?> vaultTemplateClass = Class.forName("org.springframework.vault.core.VaultTemplate");
            Object vaultTemplate = applicationContext.getBean(vaultTemplateClass);
            Object response = vaultTemplateClass.getMethod("read", String.class)
                    .invoke(vaultTemplate, "secret/logcollect");
            if (response == null) {
                return null;
            }
            Object data = response.getClass().getMethod("getData").invoke(response);
            if (!(data instanceof Map)) {
                return null;
            }
            Object key = ((Map<?, ?>) data).get("degrade-file-key");
            if (key == null) {
                return null;
            }
            return Base64.getDecoder().decode(String.valueOf(key));
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Resolve key from Spring Vault failed", t);
            return null;
        }
    }

    private static boolean isProductionProfile(String profile) {
        if (profile == null) {
            return false;
        }
        String p = profile.toLowerCase();
        return p.contains("prod") || p.contains("production");
    }
}
