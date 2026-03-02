package com.logcollect.config.springcloud;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class SpringCloudConfigSource implements LogCollectConfigSource, InitializingBean {

    @Value("${logcollect.config.spring-cloud.enabled:false}")
    private boolean enabled;

    @Autowired(required = false)
    private ConfigurableEnvironment environment;

    private volatile Properties cachedProperties = new Properties();
    private static final String DEFAULT_SOURCE_MARK = "logcollect-default";
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<Consumer<String>>();

    @Override
    public void afterPropertiesSet() {
        if (enabled) {
            refresh();
        }
    }

    @EventListener
    public void onEnvironmentChange(Object event) {
        if (event != null && "org.springframework.cloud.context.environment.EnvironmentChangeEvent"
                .equals(event.getClass().getName())) {
            refresh();
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
    public Map<String, String> getAllProperties() {
        Properties properties = cachedProperties;
        if (properties == null || properties.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("logcollect.")) {
                result.put(key, properties.getProperty(key));
            }
        }
        return result;
    }

    @Override
    public void addChangeListener(Consumer<String> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String getType() {
        return "spring-cloud";
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void refresh() {
        if (!enabled || environment == null) {
            return;
        }
        try {
            Properties props = new Properties();
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source.getName() != null && source.getName().contains(DEFAULT_SOURCE_MARK)) {
                    continue;
                }
                if (!(source instanceof EnumerablePropertySource)) {
                    continue;
                }
                String[] propertyNames = ((EnumerablePropertySource<?>) source).getPropertyNames();
                for (String propertyName : propertyNames) {
                    if (propertyName == null || !propertyName.startsWith("logcollect.")) {
                        continue;
                    }
                    Object value = source.getProperty(propertyName);
                    if (value != null) {
                        props.setProperty(propertyName, value.toString());
                    }
                }
            }
            cachedProperties = props;
            for (Consumer<String> listener : listeners) {
                try {
                    listener.accept("spring-cloud");
                } catch (Exception ignored) {
                } catch (Error e) {
                    throw e;
                }
            }
        } catch (Exception t) {
            LogCollectInternalLogger.warn("Refresh spring-cloud config failed", t);
        } catch (Error e) {
            throw e;
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
}
