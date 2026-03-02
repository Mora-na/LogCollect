package com.logcollect.core.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectDiagTest {

    @Test
    void setEnabledAndDebug_coverBothEnabledStates() {
        boolean original = LogCollectDiag.isEnabled();
        try {
            LogCollectDiag.setEnabled(false);
            assertThat(LogCollectDiag.isEnabled()).isFalse();
            LogCollectDiag.debug("disabled %s", "x");

            LogCollectDiag.setEnabled(true);
            assertThat(LogCollectDiag.isEnabled()).isTrue();
            LogCollectDiag.debug("enabled %s", "y");
        } finally {
            LogCollectDiag.setEnabled(original);
        }
    }
}
