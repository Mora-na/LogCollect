package com.logcollect.core.pipeline;

import com.logcollect.api.model.LogEntry;
import com.logcollect.core.security.DefaultLogMasker;
import com.logcollect.core.security.DefaultLogSanitizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

class SecurityPipelineTest {

    @Test
    void shouldSanitizeAndMaskMdcContext() {
        SecurityPipeline pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker());

        Map<String, String> mdc = new HashMap<String, String>();
        mdc.put("request id", "phone=13812345678\r\n");
        mdc.put("safe.key", "plain");

        LogEntry raw = LogEntry.builder()
                .traceId("t1")
                .content("ok")
                .level("INFO")
                .threadName("thread")
                .loggerName("logger")
                .timestamp(System.currentTimeMillis())
                .mdcContext(mdc)
                .build();

        LogEntry sanitized = pipeline.process(raw);
        Map<String, String> safeMdc = sanitized.getMdcContext();

        Assertions.assertEquals("phone=138****5678  ", safeMdc.get("request_id"));
        Assertions.assertEquals("plain", safeMdc.get("safe.key"));
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> safeMdc.put("k", "v"));
    }

    @Test
    void shouldReturnEmptyMdcWhenInputEmpty() {
        SecurityPipeline pipeline = new SecurityPipeline(new DefaultLogSanitizer(), new DefaultLogMasker());
        LogEntry raw = LogEntry.builder()
                .traceId("t2")
                .content("ok")
                .level("INFO")
                .timestamp(System.currentTimeMillis())
                .mdcContext(Collections.emptyMap())
                .build();
        LogEntry out = pipeline.process(raw);
        Assertions.assertTrue(out.getMdcContext().isEmpty());
    }
}
