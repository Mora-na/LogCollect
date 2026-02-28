package com.logcollect.core.degrade;

import com.logcollect.core.internal.LogCollectInternalLogger;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

public class DegradeKeyManager {
    public SecretKey resolveKey() {
        SecretKey key = resolveFromProviders();
        if (key != null) {
            return key;
        }
        String env = System.getenv("LOGCOLLECT_DEGRADE_FILE_KEY");
        key = decodeKey(env);
        if (key != null) {
            return key;
        }
        String prop = System.getProperty("logcollect.degrade.file.encrypt-key");
        key = decodeKey(prop);
        if (key != null) {
            LogCollectInternalLogger.warn("Using degrade file key from system property; avoid this in production");
            return key;
        }
        return null;
    }

    private SecretKey resolveFromProviders() {
        try {
            ServiceLoader<DegradeKeyProvider> loader = ServiceLoader.load(DegradeKeyProvider.class);
            List<DegradeKeyProvider> providers = new ArrayList<DegradeKeyProvider>();
            for (DegradeKeyProvider provider : loader) {
                providers.add(provider);
            }
            Collections.sort(providers, new Comparator<DegradeKeyProvider>() {
                @Override
                public int compare(DegradeKeyProvider o1, DegradeKeyProvider o2) {
                    return Integer.compare(o1.getOrder(), o2.getOrder());
                }
            });
            for (DegradeKeyProvider provider : providers) {
                SecretKey key = provider.resolve();
                if (key != null) {
                    return key;
                }
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Failed to load key providers", t);
        }
        return null;
    }

    private SecretKey decodeKey(String base64) {
        if (base64 == null || base64.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64.trim());
            if (decoded.length != 32) {
                LogCollectInternalLogger.warn("Invalid AES key length: {}", decoded.length);
                return null;
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Failed to decode AES key", t);
            return null;
        }
    }
}
