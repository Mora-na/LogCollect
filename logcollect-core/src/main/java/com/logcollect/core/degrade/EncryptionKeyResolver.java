package com.logcollect.core.degrade;

import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.context.ApplicationContext;

import java.util.Base64;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 降级文件加密密钥解析器（Spring 环境）。
 *
 * <p>按 KMS SPI、环境变量、Vault、配置中心顺序解析密钥。
 */
public final class EncryptionKeyResolver {

    private static final String ENV_KEY = "LOGCOLLECT_DEGRADE_FILE_KEY";

    private EncryptionKeyResolver() {
    }

    /**
     * 解析降级文件加密密钥。
     *
     * @param applicationContext Spring 上下文，可为 null
     * @param activeProfiles     当前激活 profile，可为 null
     * @return 密钥字节数组
     * @throws IllegalStateException 未找到可用密钥时抛出
     */
    public static byte[] resolveKey(ApplicationContext applicationContext, String[] activeProfiles) {
        for (KmsKeyProvider provider : ServiceLoader.load(KmsKeyProvider.class)) {
            try {
                byte[] key = provider.getKey("logcollect-degrade-file");
                if (key != null && key.length > 0) {
                    LogCollectInternalLogger.info("Encryption key loaded from KMS provider: {}",
                            provider.getClass().getSimpleName());
                    return key;
                }
            } catch (Exception ignored) {
            } catch (Error e) {
                throw e;
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

        String configKey = applicationContext == null
                ? null
                : applicationContext.getEnvironment().getProperty("logcollect.global.degrade.file.encrypt-key");
        if (configKey != null && !configKey.isEmpty()) {
            if (isProductionEnvironment(activeProfiles)) {
                LogCollectInternalLogger.error(
                        "SECURITY: degrade file encryption key from config is rejected in production profile. "
                                + "Use KMS/environment variable/Vault instead.");
                configKey = null;
            } else {
                LogCollectInternalLogger.warn(
                        "Degrade file encryption key loaded from config. Development only, do not use in production.");
            }
        }

        if (configKey != null && !configKey.isEmpty()) {
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
        } catch (Exception e) {
            LogCollectInternalLogger.warn("Resolve key from Spring Vault failed", e);
            return null;
        } catch (Error e) {
            throw e;
        }
    }

    private static boolean isProductionEnvironment(String[] profiles) {
        boolean hasProductionProfile = false;
        boolean hasExplicitDevTest = false;
        if (profiles != null) {
            for (String profile : profiles) {
                if (profile == null) {
                    continue;
                }
                String p = profile.trim().toLowerCase();
                if (p.matches(".*(prod|production|prd).*")) {
                    hasProductionProfile = true;
                }
                if (p.matches(".*(dev|development|test|local|staging).*")) {
                    hasExplicitDevTest = true;
                }
            }
        }
        boolean hasK8sEnv = System.getenv("KUBERNETES_SERVICE_HOST") != null;
        boolean hasCloudFoundry = System.getenv("VCAP_APPLICATION") != null;
        return hasProductionProfile || hasK8sEnv || hasCloudFoundry || !hasExplicitDevTest;
    }
}
