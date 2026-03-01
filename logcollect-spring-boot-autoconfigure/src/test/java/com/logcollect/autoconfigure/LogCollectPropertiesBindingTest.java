package com.logcollect.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(classes = {LogCollectAutoConfiguration.class}, properties = {
        "logcollect.global.level=WARN",
        "logcollect.global.buffer.max-size=200",
        "logcollect.global.degrade.fail-threshold=10",
        "logcollect.global.degrade.file.ttl-days=30",
        "logcollect.global.security.sanitize.enabled=false",
        "logcollect.internal.log-level=DEBUG"
})
class LogCollectPropertiesBindingTest {

    @Autowired
    private LogCollectProperties properties;

    @Test
    void shouldBindAllProperties() {
        assertEquals("WARN", properties.getGlobal().getLevel());
        assertEquals(200, properties.getGlobal().getBuffer().getMaxSize());
        assertEquals(10, properties.getGlobal().getDegrade().getFailThreshold());
        assertEquals(30, properties.getGlobal().getDegrade().getFile().getTtlDays());
        assertFalse(properties.getGlobal().getSecurity().getSanitize().isEnabled());
        assertEquals("DEBUG", properties.getInternal().getLogLevel());
    }
}
