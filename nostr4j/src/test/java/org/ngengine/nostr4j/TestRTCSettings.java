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

import org.junit.Test;

public class TestRTCSettings {

    @Test
    public void testFactoryRequiresBothIdsAndUsesDefaultMaxDirectPeers() {
        assertEquals(16, RTCSettings.DEFAULT_MAX_DIRECT_PEERS);
        RTCSettings settings = RTCSettings.getDefault("test.app", "test.protocol");
        assertEquals(16, settings.getMaxDirectPeers());
        assertEquals("test.app", settings.getApplicationId());
        assertEquals("test.protocol", settings.getProtocolId());
        try {
            RTCSettings.getDefault(null, "test.protocol");
            fail("Null applicationId must be rejected");
        } catch (NullPointerException expected) {
            // Expected.
        }
        try {
            RTCSettings.getDefault("test.app", " ");
            fail("Blank protocolId must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void testWithMaxDirectPeersReturnsIndependentImmutableValue() {
        RTCSettings original = RTCSettings.getDefault("test.app", "test.protocol");
        RTCSettings changed = original.withMaxDirectPeers(7);

        assertNotSame(original, changed);
        assertEquals(16, original.getMaxDirectPeers());
        assertEquals(7, changed.getMaxDirectPeers());
        assertNotEquals(original, changed);
        assertEquals(changed, changed.withMaxDirectPeers(7));
    }

    @Test
    public void testMaxDirectPeersMinimumIsTwo() {
        assertEquals(2, RTCSettings.getDefault("test.app", "test.protocol").withMaxDirectPeers(2).getMaxDirectPeers());
        try {
            RTCSettings.getDefault("test.app", "test.protocol").withMaxDirectPeers(1);
            fail("maxDirectPeers below two must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("maxDirectPeers must be at least 2", expected.getMessage());
        }
    }

    @Test
    public void testMaxDirectPeersMaximumIsSixtyFour() {
        assertEquals(64, RTCSettings.getDefault("test.app", "test.protocol").withMaxDirectPeers(64).getMaxDirectPeers());
        try {
            RTCSettings.getDefault("test.app", "test.protocol").withMaxDirectPeers(65);
            fail("maxDirectPeers above sixty-four must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("maxDirectPeers must not exceed 64", expected.getMessage());
        }
    }
}
