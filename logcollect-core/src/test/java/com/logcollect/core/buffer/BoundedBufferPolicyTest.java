package com.logcollect.core.buffer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedBufferPolicyTest {

    @Test
    void flushEarly_exceedsThreshold_requestsFlushAndAccept() {
        BoundedBufferPolicy policy = new BoundedBufferPolicy(1024, 1, BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY);
        AtomicBoolean flushed = new AtomicBoolean(false);
        policy.beforeAdd(100, null);
        BoundedBufferPolicy.RejectReason action = policy.beforeAdd(100, () -> flushed.set(true));
        assertThat(flushed.get()).isTrue();
        assertThat(action).isEqualTo(BoundedBufferPolicy.RejectReason.ACCEPTED);
    }

    @Test
    void dropOldest_exceedsThreshold_requestsAcceptForCallerEviction() {
        BoundedBufferPolicy policy = new BoundedBufferPolicy(1024, 1, BoundedBufferPolicy.OverflowStrategy.DROP_OLDEST);
        policy.beforeAdd(100, null);
        BoundedBufferPolicy.RejectReason action = policy.beforeAdd(100, null);
        assertThat(action).isEqualTo(BoundedBufferPolicy.RejectReason.ACCEPTED);
    }

    @Test
    void dropNewest_exceedsThreshold_requestsReject() {
        BoundedBufferPolicy policy = new BoundedBufferPolicy(1024, 1, BoundedBufferPolicy.OverflowStrategy.DROP_NEWEST);
        BoundedBufferPolicy.RejectReason first = policy.beforeAdd(100, null);
        BoundedBufferPolicy.RejectReason second = policy.beforeAdd(100, null);
        assertThat(first).isEqualTo(BoundedBufferPolicy.RejectReason.ACCEPTED);
        assertThat(second).isEqualTo(BoundedBufferPolicy.RejectReason.BUFFER_FULL);
    }

    @Test
    void anyPolicy_belowThreshold_requestsAccept() {
        for (BoundedBufferPolicy.OverflowStrategy strategy : BoundedBufferPolicy.OverflowStrategy.values()) {
            BoundedBufferPolicy policy = new BoundedBufferPolicy(1024, 10, strategy);
            BoundedBufferPolicy.RejectReason result = policy.beforeAdd(100, null);
            assertThat(result).isEqualTo(BoundedBufferPolicy.RejectReason.ACCEPTED);
        }
    }

    @Test
    void flushEarly_exceedsBytesThreshold_requestsFlush() {
        BoundedBufferPolicy policy = new BoundedBufferPolicy(10, 100, BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY);
        AtomicBoolean flushed = new AtomicBoolean(false);
        policy.beforeAdd(8, null);
        policy.beforeAdd(8, () -> flushed.set(true));
        assertThat(flushed.get()).isTrue();
    }

    @Test
    void afterDrain_reducesCounters() {
        BoundedBufferPolicy policy = new BoundedBufferPolicy(1024, 100, BoundedBufferPolicy.OverflowStrategy.FLUSH_EARLY);
        policy.beforeAdd(100, null);
        policy.beforeAdd(200, null);
        policy.afterDrain(150, 1);
        assertThat(policy.getCurrentBytes()).isEqualTo(150);
        assertThat(policy.getCurrentCount()).isEqualTo(1);
    }
}
