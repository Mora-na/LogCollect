package com.logcollect.config.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.exception.NacosException;
import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

public class NacosLogCollectConfigSource implements LogCollectConfigSource, InitializingBean, DisposableBean {

    @Value("${logcollect.config.nacos.enabled:false}")
    private boolean enabled;

    @Value("${logcollect.config.nacos.data-id:logcollect-config}")
    private String dataId;

    @Value("${logcollect.config.nacos.group:DEFAULT_GROUP}")
    private String group;

    @Value("${logcollect.config.nacos.server-addr:${spring.cloud.nacos.config.server-addr:}}")
    private String serverAddr;

    private volatile Properties cachedProperties = new Properties();
    private volatile ConfigService configService;

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();

    @Override
    public void afterPropertiesSet() {
        if (!enabled || serverAddr == null || serverAddr.trim().isEmpty()) {
            return;
        }
        try {
            Properties nacosProps = new Properties();
            nacosProps.setProperty("serverAddr", serverAddr);
            this.configService = NacosFactory.createConfigService(nacosProps);
            refresh();
            configService.addListener(dataId, group, new AbstractListener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    onConfigChanged(configInfo);
                }
            });
            LogCollectInternalLogger.info("LogCollect Nacos config source initialized. dataId={}, group={}", dataId, group);
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Init Nacos config source failed", t);
            loadFromLocalCache();
        }
    }

    @Override
    public Map<String, String> getGlobalProperties() {
        return extractByPrefix("logcollect.global.");
    }

    @Override
    public Map<String, String> getMethodProperties(String methodKey) {
        return extractByPrefix("logcollect.methods." + methodKey + ".");
    }

    @Override
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String getType() {
        return "nacos";
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void refresh() {
        if (!enabled || configService == null) {
            loadFromLocalCache();
            return;
        }
        try {
            String content = configService.getConfig(dataId, group, 5000);
            if (content == null || content.trim().isEmpty()) {
                loadFromLocalCache();
                return;
            }
            Properties properties = new Properties();
            properties.load(new StringReader(content));
            cachedProperties = properties;
            saveToLocalCache(properties);
            notifyListeners();
        } catch (NacosException e) {
            LogCollectInternalLogger.warn("Fetch Nacos config failed, fallback to local cache", e);
            loadFromLocalCache();
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Refresh Nacos config failed", t);
            loadFromLocalCache();
        }
    }

    @Override
    public void destroy() {
        ConfigService service = this.configService;
        if (service != null) {
            try {
                service.shutDown();
            } catch (NacosException e) {
                LogCollectInternalLogger.warn("Shutdown Nacos config service failed", e);
            }
        }
    }

    private void onConfigChanged(String configInfo) {
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(configInfo));
            cachedProperties = properties;
            saveToLocalCache(properties);
            notifyListeners();
            LogCollectInternalLogger.info("Nacos config changed, {} keys refreshed", properties.size());
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Process Nacos config change failed", t);
        }
    }

    private Map<String, String> extractByPrefix(String prefix) {
        Properties properties = cachedProperties;
        if (properties == null || properties.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                result.put(key.substring(prefix.length()), properties.getProperty(key));
            }
        }
        return result;
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private void saveToLocalCache(Properties properties) {
        try {
            File dir = new File(getLocalCacheDir());
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, "nacos-" + dataId + ".properties");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                properties.store(fos, "LogCollect nacos cache " + LocalDateTime.now());
            }
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Save Nacos local cache failed", t);
        }
    }

    private void loadFromLocalCache() {
        try {
            File file = new File(getLocalCacheDir(), "nacos-" + dataId + ".properties");
            if (!file.exists()) {
                return;
            }
            long ageMs = System.currentTimeMillis() - file.lastModified();
            long maxAgeMs = 7L * 24 * 3600 * 1000;
            if (ageMs > maxAgeMs) {
                return;
            }
            Properties properties = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                properties.load(fis);
            }
            cachedProperties = properties;
        } catch (Throwable t) {
            LogCollectInternalLogger.warn("Load Nacos local cache failed", t);
        }
    }

    private String getLocalCacheDir() {
        return System.getProperty("user.home") + "/.logcollect/config-cache";
    }
}
