package com.logcollect.api.model;

import com.logcollect.api.backpressure.BackpressureAction;
import com.logcollect.api.backpressure.BackpressureCallback;
import com.logcollect.api.enums.*;
import com.logcollect.api.handler.LogCollectHandler;
import com.logcollect.api.masker.LogMasker;
import com.logcollect.api.sanitizer.LogSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectConfigTest {

    @Test
    void frameworkDefaults_and_settersGetters_work() {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getLevel()).isEqualTo("INFO");

        config.setEnabled(false);
        config.setHandlerClass(LogCollectHandler.class);
        config.setAsync(false);
        config.setLevel("WARN");
        config.setExcludeLoggerPrefixes(new String[]{"a.", "b."});
        config.setLogFramework(LogFramework.LOG4J2);
        config.setCollectMode(CollectMode.SINGLE);
        config.setEffectiveCollectMode(CollectMode.AGGREGATE);
        config.setUseBuffer(false);
        config.setMaxBufferSize(200);
        config.setMaxBufferBytes(2048L);
        config.setBufferOverflowStrategy("DROP_NEWEST");
        config.setGlobalBufferTotalMaxBytes(4096L);
        config.setEnableDegrade(false);
        config.setDegradeFailThreshold(8);
        config.setDegradeStorage(DegradeStorage.LIMITED_MEMORY);
        config.setRecoverIntervalSeconds(10);
        config.setRecoverMaxIntervalSeconds(20);
        config.setHalfOpenPassCount(2);
        config.setHalfOpenSuccessThreshold(2);
        config.setDegradeWindowSize(12);
        config.setDegradeFailureRateThreshold(0.7d);
        config.setBlockWhenDegradeFail(true);
        config.setEnableSanitize(false);
        config.setSanitizerClass(LogSanitizer.class);
        config.setEnableMask(false);
        config.setMaskerClass(LogMasker.class);
        config.setGuardMaxContentLength(111);
        config.setGuardMaxThrowableLength(222);
        config.setDegradeFileMaxTotalSize("1GB");
        config.setDegradeFileTTLDays(15);
        config.setEnableDegradeFileEncrypt(true);
        config.setHandlerTimeoutMs(3210);
        config.setTransactionIsolation(true);
        config.setMaxNestingDepth(6);
        config.setMaxTotalCollect(999);
        config.setMaxTotalCollectBytes(88_888L);
        config.setTotalLimitPolicy(TotalLimitPolicy.SAMPLE);
        config.setSamplingRate(0.3d);
        config.setSamplingStrategy(SamplingStrategy.ADAPTIVE);
        config.setBackpressureCallbackClass(TestBackpressure.class);
        config.setEnableMetrics(false);

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getHandlerClass()).isEqualTo(LogCollectHandler.class);
        assertThat(config.isAsync()).isFalse();
        assertThat(config.getLevel()).isEqualTo("WARN");
        assertThat(config.getExcludeLoggerPrefixes()).containsExactly("a.", "b.");
        assertThat(config.getLogFramework()).isEqualTo(LogFramework.LOG4J2);
        assertThat(config.getCollectMode()).isEqualTo(CollectMode.SINGLE);
        assertThat(config.getEffectiveCollectMode()).isEqualTo(CollectMode.AGGREGATE);
        assertThat(config.isUseBuffer()).isFalse();
        assertThat(config.getMaxBufferSize()).isEqualTo(200);
        assertThat(config.getMaxBufferBytes()).isEqualTo(2048L);
        assertThat(config.getBufferOverflowStrategy()).isEqualTo("DROP_NEWEST");
        assertThat(config.getGlobalBufferTotalMaxBytes()).isEqualTo(4096L);
        assertThat(config.isEnableDegrade()).isFalse();
        assertThat(config.getDegradeFailThreshold()).isEqualTo(8);
        assertThat(config.getDegradeStorage()).isEqualTo(DegradeStorage.LIMITED_MEMORY);
        assertThat(config.getRecoverIntervalSeconds()).isEqualTo(10);
        assertThat(config.getRecoverMaxIntervalSeconds()).isEqualTo(20);
        assertThat(config.getHalfOpenPassCount()).isEqualTo(2);
        assertThat(config.getHalfOpenSuccessThreshold()).isEqualTo(2);
        assertThat(config.getDegradeWindowSize()).isEqualTo(12);
        assertThat(config.getDegradeFailureRateThreshold()).isEqualTo(0.7d);
        assertThat(config.isBlockWhenDegradeFail()).isTrue();
        assertThat(config.isEnableSanitize()).isFalse();
        assertThat(config.getSanitizerClass()).isEqualTo(LogSanitizer.class);
        assertThat(config.isEnableMask()).isFalse();
        assertThat(config.getMaskerClass()).isEqualTo(LogMasker.class);
        assertThat(config.getGuardMaxContentLength()).isEqualTo(111);
        assertThat(config.getGuardMaxThrowableLength()).isEqualTo(222);
        assertThat(config.getDegradeFileMaxTotalSize()).isEqualTo("1GB");
        assertThat(config.getDegradeFileTTLDays()).isEqualTo(15);
        assertThat(config.isEnableDegradeFileEncrypt()).isTrue();
        assertThat(config.getHandlerTimeoutMs()).isEqualTo(3210);
        assertThat(config.isTransactionIsolation()).isTrue();
        assertThat(config.getMaxNestingDepth()).isEqualTo(6);
        assertThat(config.getMaxTotalCollect()).isEqualTo(999);
        assertThat(config.getMaxTotalCollectBytes()).isEqualTo(88_888L);
        assertThat(config.getTotalLimitPolicy()).isEqualTo(TotalLimitPolicy.SAMPLE);
        assertThat(config.getSamplingRate()).isEqualTo(0.3d);
        assertThat(config.getSamplingStrategy()).isEqualTo(SamplingStrategy.ADAPTIVE);
        assertThat(config.getBackpressureCallbackClass()).isEqualTo(TestBackpressure.class);
        assertThat(config.isEnableMetrics()).isFalse();
    }

    @Test
    void excludeLoggerPrefixes_returnsCopy() {
        LogCollectConfig config = LogCollectConfig.frameworkDefaults();
        String[] prefixes = new String[]{"x."};
        config.setExcludeLoggerPrefixes(prefixes);
        prefixes[0] = "changed";

        String[] read = config.getExcludeLoggerPrefixes();
        read[0] = "mutated";
        assertThat(config.getExcludeLoggerPrefixes()).containsExactly("x.");

        config.setExcludeLoggerPrefixes(null);
        assertThat(config.getExcludeLoggerPrefixes()).isEmpty();
    }

    public static class TestBackpressure implements BackpressureCallback {
        @Override
        public BackpressureAction onPressure(double utilization) {
            return BackpressureAction.CONTINUE;
        }
    }
}
