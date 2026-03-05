package com.logcollect.config.apollo;

import com.logcollect.api.config.LogCollectConfigSource;
import com.logcollect.core.internal.LogCollectInternalLogger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ApolloLogCollectConfigSource implements LogCollectConfigSource, InitializingBean {

    @Value("${logcollect.config.apollo.enabled:false}")
    private boolean enabled;

    @Value("${logcollect.config.apollo.namespace:application}")
    private String namespace;

    private volatile Properties cachedProperties = new Properties();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<Consumer<String>>();
    private final CopyOnWriteArrayList<BiConsumer<String, Map<String, String>>> diffListeners =
            new CopyOnWriteArrayList<BiConsumer<String, Map<String, String>>>();

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        refresh();
        registerApolloChangeListener();
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
        Properties properties = this.cachedProperties;
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
    public void addChangeListener(BiConsumer<String, Map<String, String>> listener) {
        if (listener != null) {
            diffListeners.add(listener);
        }
    }

    @Override
    public String getType() {
        return "apollo";
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
        if (!enabled) {
            return;
        }
        try {
            Object config = getApolloConfig();
            if (config == null) {
                return;
            }
            Properties properties = new Properties();
            Method getPropertyNames = config.getClass().getMethod("getPropertyNames");
            Method getProperty = config.getClass().getMethod("getProperty", String.class, String.class);
            Iterable<?> names = (Iterable<?>) getPropertyNames.invoke(config);
            if (names != null) {
                for (Object keyObj : names) {
                    if (keyObj == null) {
                        continue;
                    }
                    String key = keyObj.toString();
                    Object value = getProperty.invoke(config, key, null);
                    if (value != null) {
                        properties.setProperty(key, value.toString());
                    }
                }
            }
            Map<String, String> changed = diff(this.cachedProperties, properties);
            this.cachedProperties = properties;
            notifyListeners(changed);
            LogCollectInternalLogger.info("Apollo config refreshed, {} keys", properties.size());
        } catch (Exception t) {
            LogCollectInternalLogger.warn("Refresh Apollo config failed", t);
        } catch (Error e) {
            throw e;
        }
    }

    private void registerApolloChangeListener() {
        try {
            final Object config = getApolloConfig();
            if (config == null) {
                return;
            }
            Class<?> listenerClass = Class.forName("com.ctrip.framework.apollo.model.ConfigChangeListener");
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class[]{listenerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onChange".equals(method.getName())) {
                                refresh();
                            }
                            return null;
                        }
                    });
            Method addChangeListener = config.getClass().getMethod("addChangeListener", listenerClass);
            addChangeListener.invoke(config, listener);
        } catch (Exception t) {
            LogCollectInternalLogger.warn("Register Apollo config listener failed", t);
        } catch (Error e) {
            throw e;
        }
    }

    private Object getApolloConfig() throws Exception {
        Class<?> configServiceClass = Class.forName("com.ctrip.framework.apollo.ConfigService");
        Method getConfigMethod = configServiceClass.getMethod("getConfig", String.class);
        return getConfigMethod.invoke(null, namespace);
    }

    private Map<String, String> extractByPrefix(String prefix) {
        Properties properties = this.cachedProperties;
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

    private void notifyListeners(Map<String, String> changed) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept("apollo");
            } catch (Exception ignored) {
            } catch (Error e) {
                throw e;
            }
        }
        Map<String, String> snapshot = changed == null ? Collections.<String, String>emptyMap() : changed;
        for (BiConsumer<String, Map<String, String>> listener : diffListeners) {
            try {
                listener.accept("apollo", snapshot);
            } catch (Exception ignored) {
            } catch (Error e) {
                throw e;
            }
        }
    }

    private Map<String, String> diff(Properties before, Properties after) {
        Map<String, String> changed = new LinkedHashMap<String, String>();
        Properties oldProps = before == null ? new Properties() : before;
        Properties newProps = after == null ? new Properties() : after;
        for (String key : newProps.stringPropertyNames()) {
            if (!key.startsWith("logcollect.")) {
                continue;
            }
            String oldVal = oldProps.getProperty(key);
            String newVal = newProps.getProperty(key);
            if (!String.valueOf(oldVal).equals(String.valueOf(newVal))) {
                changed.put(key, newVal);
            }
        }
        for (String key : oldProps.stringPropertyNames()) {
            if (!key.startsWith("logcollect.")) {
                continue;
            }
            if (!newProps.containsKey(key)) {
                changed.put(key, null);
            }
        }
        return changed;
    }
}
