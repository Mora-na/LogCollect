package com.logcollect.core.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogCollectGlobalSwitchTest {

    @Test
    void switchState_setEnabledAndOnConfigChange_coverAllBranches() {
        LogCollectGlobalSwitch globalSwitch = new LogCollectGlobalSwitch(true);
        assertThat(globalSwitch.isEnabled()).isTrue();

        boolean previous = globalSwitch.setEnabled(false);
        assertThat(previous).isTrue();
        assertThat(globalSwitch.isEnabled()).isFalse();

        previous = globalSwitch.setEnabled(false);
        assertThat(previous).isFalse();
        assertThat(globalSwitch.isEnabled()).isFalse();

        globalSwitch.onConfigChange(true);
        assertThat(globalSwitch.isEnabled()).isTrue();

        globalSwitch.onConfigChange(true);
        assertThat(globalSwitch.isEnabled()).isTrue();
    }
}
