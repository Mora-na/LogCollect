package com.logcollect.benchmark.gate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JmhCIGateThresholdTest {

    @Test
    void maxAllowedSlowdownMultiplier_tieredByBaselineNs() {
        assertEquals(10.0d, JmhCIGateTest.maxAllowedSlowdownMultiplier(9.99d));
        assertEquals(4.0d, JmhCIGateTest.maxAllowedSlowdownMultiplier(10.0d));
        assertEquals(4.0d, JmhCIGateTest.maxAllowedSlowdownMultiplier(100.0d));
        assertEquals(3.0d, JmhCIGateTest.maxAllowedSlowdownMultiplier(100.01d));
    }
}
