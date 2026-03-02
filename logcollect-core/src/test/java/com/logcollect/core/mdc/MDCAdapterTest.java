package com.logcollect.core.mdc;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MDCAdapterTest {

    @Test
    void putRemoveGetCopySetContextMap_allSafe() {
        assertDoesNotThrow(() -> MDCAdapter.put("k1", "v1"));
        Map<String, String> map = MDCAdapter.getCopyOfContextMap();
        assertThat(map).isNotNull();

        assertDoesNotThrow(() -> MDCAdapter.remove("k1"));
        assertDoesNotThrow(() -> MDCAdapter.setContextMap(Collections.singletonMap("a", "b")));
        assertThat(MDCAdapter.getCopyOfContextMap()).isNotNull();
    }

    @Test
    void methods_withNullInputs_safe() {
        assertDoesNotThrow(() -> MDCAdapter.put(null, null));
        assertDoesNotThrow(() -> MDCAdapter.remove(null));
        assertDoesNotThrow(() -> MDCAdapter.setContextMap(null));
        assertThat(MDCAdapter.getCopyOfContextMap()).isNotNull();
        MDC.clear();
    }
}
