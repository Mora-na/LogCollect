package com.logcollect.core.security;

import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.model.LogCollectConfig;
import com.logcollect.api.sanitizer.LogSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityComponentRegistryTest {

    @Test
    void getSanitizer_and_getMasker_whenConfigNull_returnsNoOp() {
        SecurityComponentRegistry registry = new SecurityComponentRegistry(null);
        assertThat(registry.getSanitizer(null)).isSameAs(NoOpLogSanitizer.INSTANCE);
        assertThat(registry.getMasker(null)).isSameAs(NoOpLogMasker.INSTANCE);
    }

    @Test
    void getSanitizer_and_getMasker_whenFeatureDisabled_returnsNoOp() {
        SecurityComponentRegistry registry = new SecurityComponentRegistry(null);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setEnableSanitize(false);
        config.setEnableMask(false);

        assertThat(registry.getSanitizer(config)).isSameAs(NoOpLogSanitizer.INSTANCE);
        assertThat(registry.getMasker(config)).isSameAs(NoOpLogMasker.INSTANCE);
    }

    @Test
    void getSanitizer_and_getMasker_defaultWithoutApplicationContext_returnsDefaultImpl() {
        SecurityComponentRegistry registry = new SecurityComponentRegistry(null);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();

        assertThat(registry.getSanitizer(config)).isInstanceOf(DefaultLogSanitizer.class);
        assertThat(registry.getMasker(config)).isInstanceOf(DefaultLogMasker.class);
    }

    @Test
    void getSanitizer_and_getMasker_defaultPreferApplicationContextBean() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        LogSanitizer sanitizerBean = new LogSanitizer() {
            @Override
            public String sanitize(String raw) {
                return "ctx-" + raw;
            }

            @Override
            public String sanitizeThrowable(String throwableString) {
                return "ctx-" + throwableString;
            }
        };
        LogMasker maskerBean = content -> "ctx-" + content;
        when(applicationContext.getBean(LogSanitizer.class)).thenReturn(sanitizerBean);
        when(applicationContext.getBean(LogMasker.class)).thenReturn(maskerBean);

        SecurityComponentRegistry registry = new SecurityComponentRegistry(applicationContext);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();

        assertThat(registry.getSanitizer(config)).isSameAs(sanitizerBean);
        assertThat(registry.getMasker(config)).isSameAs(maskerBean);
    }

    @Test
    void getSanitizer_and_getMasker_whenContextMissingBean_fallbackToDefault() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(LogSanitizer.class))
                .thenThrow(new NoSuchBeanDefinitionException(LogSanitizer.class));
        when(applicationContext.getBean(LogMasker.class))
                .thenThrow(new NoSuchBeanDefinitionException(LogMasker.class));

        SecurityComponentRegistry registry = new SecurityComponentRegistry(applicationContext);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();

        assertThat(registry.getSanitizer(config)).isInstanceOf(DefaultLogSanitizer.class);
        assertThat(registry.getMasker(config)).isInstanceOf(DefaultLogMasker.class);
    }

    @Test
    void getSanitizer_and_getMasker_customClass_isCached() {
        SecurityComponentRegistry registry = new SecurityComponentRegistry(null);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setSanitizerClass(CustomSanitizer.class);
        config.setMaskerClass(CustomMasker.class);

        LogSanitizer s1 = registry.getSanitizer(config);
        LogSanitizer s2 = registry.getSanitizer(config);
        LogMasker m1 = registry.getMasker(config);
        LogMasker m2 = registry.getMasker(config);

        assertThat(s1).isInstanceOf(CustomSanitizer.class);
        assertThat(m1).isInstanceOf(CustomMasker.class);
        assertThat(s1).isSameAs(s2);
        assertThat(m1).isSameAs(m2);
    }

    @Test
    void getSanitizer_and_getMasker_customClassPreferContextBean() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CustomSanitizer sanitizerBean = new CustomSanitizer();
        CustomMasker maskerBean = new CustomMasker();
        when(applicationContext.getBean(CustomSanitizer.class)).thenReturn(sanitizerBean);
        when(applicationContext.getBean(CustomMasker.class)).thenReturn(maskerBean);

        SecurityComponentRegistry registry = new SecurityComponentRegistry(applicationContext);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setSanitizerClass(CustomSanitizer.class);
        config.setMaskerClass(CustomMasker.class);

        assertThat(registry.getSanitizer(config)).isSameAs(sanitizerBean);
        assertThat(registry.getMasker(config)).isSameAs(maskerBean);
    }

    @Test
    void instantiate_whenContextMissingCustomBean_fallbackToReflectionInstance() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(CustomSanitizer.class))
                .thenThrow(new NoSuchBeanDefinitionException(CustomSanitizer.class));
        when(applicationContext.getBean(CustomMasker.class))
                .thenThrow(new NoSuchBeanDefinitionException(CustomMasker.class));

        SecurityComponentRegistry registry = new SecurityComponentRegistry(applicationContext);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setSanitizerClass(CustomSanitizer.class);
        config.setMaskerClass(CustomMasker.class);

        assertThat(registry.getSanitizer(config)).isInstanceOf(CustomSanitizer.class);
        assertThat(registry.getMasker(config)).isInstanceOf(CustomMasker.class);
    }

    @Test
    void instantiateFailure_throwsIllegalState() {
        SecurityComponentRegistry registry = new SecurityComponentRegistry(null);
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        config.setSanitizerClass(InvalidSanitizer.class);

        assertThatThrownBy(() -> registry.getSanitizer(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot instantiate");
    }

    @Test
    void noOpImplementations_returnOriginalInput() {
        assertThat(NoOpLogSanitizer.INSTANCE.sanitize("x")).isEqualTo("x");
        assertThat(NoOpLogSanitizer.INSTANCE.sanitizeThrowable("t")).isEqualTo("t");
        assertThat(NoOpLogMasker.INSTANCE.mask("m")).isEqualTo("m");
    }

    public static class CustomSanitizer implements LogSanitizer {
        @Override
        public String sanitize(String raw) {
            return "safe-" + raw;
        }

        @Override
        public String sanitizeThrowable(String throwableString) {
            return "safe-" + throwableString;
        }
    }

    public static class CustomMasker implements LogMasker {
        @Override
        public String mask(String content) {
            return "mask-" + content;
        }
    }

    public static class InvalidSanitizer implements LogSanitizer {
        public InvalidSanitizer(String ignored) {
        }

        @Override
        public String sanitize(String raw) {
            return raw;
        }

        @Override
        public String sanitizeThrowable(String throwableString) {
            return throwableString;
        }
    }
}
