/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import java.time.Duration;
import org.junit.Test;

public class TestRTCSettings {

    @Test
    public void testLegacyConstructorsUseDefaultMaxDirectPeers() {
        RTCSettings fiveArguments = new RTCSettings(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(4),
            Duration.ofSeconds(5)
        );
        RTCSettings sixArguments = new RTCSettings(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(4),
            Duration.ofSeconds(5),
            Duration.ofSeconds(6)
        );
        RTCSettings sevenArguments = new RTCSettings(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(4),
            Duration.ofSeconds(5),
            Duration.ofSeconds(6),
            Duration.ofSeconds(7)
        );

        assertEquals(16, RTCSettings.DEFAULT_MAX_DIRECT_PEERS);
        assertEquals(16, RTCSettings.DEFAULT.getMaxDirectPeers());
        assertEquals(16, fiveArguments.getMaxDirectPeers());
        assertEquals(16, sixArguments.getMaxDirectPeers());
        assertEquals(16, sevenArguments.getMaxDirectPeers());
    }

    @Test
    public void testWithMaxDirectPeersReturnsIndependentImmutableValue() {
        RTCSettings original = RTCSettings.DEFAULT;
        RTCSettings changed = original.withMaxDirectPeers(7);

        assertNotSame(original, changed);
        assertEquals(16, original.getMaxDirectPeers());
        assertEquals(7, changed.getMaxDirectPeers());
        assertNotEquals(original, changed);
        assertEquals(changed, changed.withMaxDirectPeers(7));
    }

    @Test
    public void testMaxDirectPeersMinimumIsTwo() {
        assertEquals(2, RTCSettings.DEFAULT.withMaxDirectPeers(2).getMaxDirectPeers());
        try {
            RTCSettings.DEFAULT.withMaxDirectPeers(1);
            fail("maxDirectPeers below two must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("maxDirectPeers must be at least 2", expected.getMessage());
        }
    }

    @Test
    public void testMaxDirectPeersMaximumIsSixtyFour() {
        assertEquals(64, RTCSettings.DEFAULT.withMaxDirectPeers(64).getMaxDirectPeers());
        try {
            RTCSettings.DEFAULT.withMaxDirectPeers(65);
            fail("maxDirectPeers above sixty-four must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("maxDirectPeers must not exceed 64", expected.getMessage());
        }
    }
}
