/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Duration;
import org.junit.Test;

public class TestRouteTransportProfile {

    @Test
    public void testReliableNativeChannelIgnoresZeroRetransmitSentinel() {
        RouteTransportProfile profile = RouteTransportProfile.fromChannel(true, true, 0, null);

        assertEquals(RouteTransportProfile.RELIABLE_ORDERED, profile);
        assertNull(profile.getMaxRetransmits());
        assertEquals(
            InternalRoutingChannels.data(RouteTransportProfile.RELIABLE_ORDERED),
            InternalRoutingChannels.data(profile)
        );
    }

    @Test
    public void testUnreliableNativeChannelPreservesRetransmitLimit() {
        RouteTransportProfile profile = RouteTransportProfile.fromChannel(false, false, 0, null);

        assertEquals(RouteTransportProfile.UNRELIABLE_UNORDERED, profile);
        assertEquals(Integer.valueOf(0), profile.getMaxRetransmits());
    }

    @Test
    public void testPacketLifetimeTakesPrecedenceOverRetransmitSentinel() {
        Duration lifetime = Duration.ofMillis(250);
        RouteTransportProfile profile = RouteTransportProfile.fromChannel(false, false, 7, lifetime);

        assertNull(profile.getMaxRetransmits());
        assertEquals(lifetime, profile.getMaxPacketLifeTime());
    }
}
