/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class TestNeighborTrafficLimiter {

    @Test
    public void testPacketAndByteWindowsArePerNeighborAndReset() {
        NeighborTrafficLimiter limiter = new NeighborTrafficLimiter(4, 2, 2, 10, 2);
        NodeId first = node("first");
        NodeId second = node("second");
        long now = 10_000L;

        close(limiter.tryAcquire(first, 6, false, now));
        close(limiter.tryAcquire(first, 4, false, now));
        assertNull(limiter.tryAcquire(first, 1, false, now));
        assertNotNull(limiter.tryAcquire(second, 10, false, now));
        assertNotNull(limiter.tryAcquire(first, 10, false, now + 1000L));
        limiter.close();
    }

    @Test
    public void testMalformedBudgetBlocksUntilMinuteWindowResets() {
        NeighborTrafficLimiter limiter = new NeighborTrafficLimiter(4, 2, 10, 100, 2);
        NodeId peer = node("malformed");
        long now = 20_000L;

        limiter.recordMalformed(peer, now);
        limiter.recordMalformed(peer, now);
        assertNull(limiter.tryAcquire(peer, 1, false, now));
        assertNotNull(limiter.tryAcquire(peer, 1, false, now + 60_000L));
        limiter.close();
    }

    @Test
    public void testControlDecryptionsAreGloballyBoundAndReleased() {
        NeighborTrafficLimiter limiter = new NeighborTrafficLimiter(4, 2, 10, 100, 2);
        NeighborTrafficLimiter.Admission first = limiter.tryAcquire(node("first"), 1, true, 30_000L);
        NeighborTrafficLimiter.Admission second = limiter.tryAcquire(node("second"), 1, true, 30_000L);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, limiter.controlDecryptionsInFlight());
        assertNull(limiter.tryAcquire(node("third"), 1, true, 30_000L));
        first.close();
        first.close();
        assertEquals(1, limiter.controlDecryptionsInFlight());
        close(limiter.tryAcquire(node("third"), 1, true, 30_000L));
        second.close();
        assertEquals(0, limiter.controlDecryptionsInFlight());
        limiter.close();
    }

    @Test
    public void testAttackerControlledNeighborStateIsBounded() {
        NeighborTrafficLimiter limiter = new NeighborTrafficLimiter(2, 2, 10, 100, 2);
        close(limiter.tryAcquire(node("one"), 1, false, 40_000L));
        close(limiter.tryAcquire(node("two"), 1, false, 40_000L));
        close(limiter.tryAcquire(node("three"), 1, false, 40_000L));

        assertEquals(2, limiter.trackedNeighborCount());
        assertNull(limiter.tryAcquire(node("four"), RoutingLimits.MAX_ROUTED_FRAME_BYTES + 1, false, 40_000L));
        limiter.close();
    }

    private static NodeId node(String value) {
        return NodeId.fromHex(NGEUtils.bytesToHex(NGEPlatform.get().sha256(value.getBytes(StandardCharsets.UTF_8))));
    }

    private static void close(NeighborTrafficLimiter.Admission admission) {
        assertNotNull(admission);
        admission.close();
    }
}
