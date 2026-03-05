package com.logcollect.autoconfigure;

import com.logcollect.core.buffer.GlobalBufferMemoryManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {LogCollectAutoConfiguration.class}, properties = {
        "logcollect.global.buffer.total-max-bytes=200MB"
})
class GlobalBufferHardCeilingDerivationTest {

    @Autowired
    private GlobalBufferMemoryManager globalBufferMemoryManager;

    @Test
    void shouldDeriveHardCeilingFromSoftLimitWhenNotConfigured() {
        long mb = 1024L * 1024L;
        assertThat(globalBufferMemoryManager.getMaxTotalBytes()).isEqualTo(200L * mb);
        assertThat(globalBufferMemoryManager.getHardCeilingBytes()).isEqualTo(300L * mb);
    }
}
