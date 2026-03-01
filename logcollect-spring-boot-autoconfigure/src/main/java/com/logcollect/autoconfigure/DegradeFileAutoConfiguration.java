package com.logcollect.autoconfigure;

import com.logcollect.core.degrade.DegradeFileEncryptor;
import com.logcollect.core.degrade.DegradeFileManager;
import com.logcollect.core.degrade.EncryptionKeyResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@ConditionalOnProperty(name = "logcollect.global.degrade.storage", havingValue = "FILE", matchIfMissing = true)
public class DegradeFileAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DegradeFileManager degradeFileManager(LogCollectProperties properties, ApplicationContext applicationContext) {
        LogCollectProperties.DegradeFile file = properties.getGlobal().getDegrade().getFile();

        String dir = file.getBaseDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("logcollect.degrade.file.base-dir",
                    System.getProperty("user.dir") + "/logs/logCollect");
        }

        DegradeFileEncryptor encryptor = null;
        if (file.isEncryptEnabled()) {
            String profile = null;
            String[] active = applicationContext.getEnvironment().getActiveProfiles();
            if (active != null && active.length > 0) {
                profile = active[0];
            }
            byte[] key = EncryptionKeyResolver.resolveKey(applicationContext, profile);
            encryptor = new DegradeFileEncryptor(new SecretKeySpec(key, "AES"));
        }

        Path baseDir = Paths.get(dir);
        DegradeFileManager manager = new DegradeFileManager(
                baseDir,
                file.getMaxTotalSizeValue(),
                file.getTtlDays(),
                encryptor);
        manager.initialize();
        return manager;
    }
}
