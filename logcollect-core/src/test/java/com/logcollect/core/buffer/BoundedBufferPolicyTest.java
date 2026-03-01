package com.logcollect.core.buffer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BoundedBufferPolicyTest {

    @Test
    void dropNewestShouldReturnBufferFullReason() {
        BoundedBufferPolicy policy = new BoundedBufferPolicy(
                10, 1, BoundedBufferPolicy.OverflowStrategy.DROP_NEWEST);

        BoundedBufferPolicy.RejectReason first = policy.beforeAdd(8, null);
        BoundedBufferPolicy.RejectReason second = policy.beforeAdd(8, null);

        Assertions.assertEquals(BoundedBufferPolicy.RejectReason.ACCEPTED, first);
        Assertions.assertEquals(BoundedBufferPolicy.RejectReason.BUFFER_FULL, second);
    }
}
