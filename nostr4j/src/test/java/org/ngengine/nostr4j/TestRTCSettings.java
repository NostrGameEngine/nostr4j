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
    public void sessionLimitsSurviveSettingsCopiesAndSerialization() throws Exception {
        org.ngengine.nostr4j.rtc.RTCSessionLimits limits = new org.ngengine.nostr4j.rtc.RTCSessionLimits(
            64,
            4,
            16,
            64,
            java.time.Duration.ofSeconds(20)
        );
        RTCSettings original = RTCSettings.getDefault("test.app", "test.protocol");
        RTCSettings settings = original
            .withSessionLimits(limits)
            .withMaxDirectPeers(7)
            .withApplicationId("other.app")
            .withSignalingRelays(java.util.List.of());
        assertNotEquals(original.getSessionLimits(), settings.getSessionLimits());
        assertEquals(limits, settings.getSessionLimits());
        assertEquals(limits, settings.clone().getSessionLimits());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(settings);
        }
        try (
            java.io.ObjectInputStream input = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray())
            )
        ) {
            RTCSettings restored = (RTCSettings) input.readObject();
            assertEquals(settings, restored);
            assertEquals(settings.hashCode(), restored.hashCode());
        }
        java.lang.reflect.Field field = RTCSettings.class.getDeclaredField("sessionLimits");
        field.setAccessible(true);
        field.set(original, null);
        assertEquals(org.ngengine.nostr4j.rtc.RTCSessionLimits.getDefault(), original.getSessionLimits());
    }

    @Test
    public void invalidSessionQuotasAndLifetimesAreRejected() {
        org.junit.Assert.assertThrows(
            IllegalArgumentException.class,
            () -> new org.ngengine.nostr4j.rtc.RTCSessionLimits(0, 1, 1, 1, java.time.Duration.ofSeconds(1))
        );
        org.junit.Assert.assertThrows(
            IllegalArgumentException.class,
            () -> new org.ngengine.nostr4j.rtc.RTCSessionLimits(1, 1, 2, 1, java.time.Duration.ofSeconds(1))
        );
        org.junit.Assert.assertThrows(
            IllegalArgumentException.class,
            () -> new org.ngengine.nostr4j.rtc.RTCSessionLimits(1, 1, 1, 1, java.time.Duration.ZERO)
        );
        org.junit.Assert.assertThrows(
            IllegalArgumentException.class,
            () -> new org.ngengine.nostr4j.rtc.RTCSessionLimits(1, 1, 1, 1, java.time.Duration.ofSeconds(Long.MAX_VALUE))
        );
    }

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
