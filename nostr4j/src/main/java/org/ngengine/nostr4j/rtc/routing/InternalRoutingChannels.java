/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.time.Duration;

public final class InternalRoutingChannels {

    public static final String RESERVED_PREFIX = "__nipdc_dc4_route/";
    public static final String CONTROL = RESERVED_PREFIX + "control";
    public static final String BROADCAST_PREFIX = RESERVED_PREFIX + "broadcast/";

    public static boolean isReserved(String label) {
        return label != null && label.startsWith(RESERVED_PREFIX);
    }

    public static String data(RouteTransportProfile profile) {
        int retransmits = profile.getMaxRetransmits() == null ? -1 : profile.getMaxRetransmits().intValue();
        Duration lifetime = profile.getMaxPacketLifeTime();
        long lifetimeMillis = lifetime == null ? -1L : lifetime.toMillis();
        return (
            RESERVED_PREFIX +
            "data/o" +
            (profile.isOrdered() ? '1' : '0') +
            "r" +
            (profile.isReliable() ? '1' : '0') +
            "x" +
            retransmits +
            "l" +
            lifetimeMillis
        );
    }

    public static String broadcast(RouteTransportProfile profile) {
        return BROADCAST_PREFIX + data(profile).substring((RESERVED_PREFIX + "data/").length());
    }

    public static RouteTransportProfile profile(String label) {
        if (CONTROL.equals(label)) return RouteTransportProfile.RELIABLE_ORDERED;
        if (label == null || !isReserved(label)) return null;
        String encoded;
        if (label.startsWith(BROADCAST_PREFIX)) {
            encoded = label.substring(BROADCAST_PREFIX.length());
        } else if (label.startsWith(RESERVED_PREFIX + "data/")) {
            encoded = label.substring((RESERVED_PREFIX + "data/").length());
        } else {
            return null;
        }
        try {
            int r = encoded.indexOf('r');
            int x = encoded.indexOf('x');
            int l = encoded.indexOf('l');
            if (encoded.length() < 7 || encoded.charAt(0) != 'o' || r != 2 || x <= r + 1 || l <= x + 1) {
                return null;
            }
            boolean ordered = parseBoolean(encoded.substring(1, r));
            boolean reliable = parseBoolean(encoded.substring(r + 1, x));
            int retransmits = Integer.parseInt(encoded.substring(x + 1, l));
            long lifetimeMillis = Long.parseLong(encoded.substring(l + 1));
            if (retransmits < -1 || lifetimeMillis < -1 || (retransmits >= 0 && lifetimeMillis >= 0)) {
                return null;
            }
            return new RouteTransportProfile(
                ordered,
                reliable,
                retransmits < 0 ? null : Integer.valueOf(retransmits),
                lifetimeMillis < 0 ? null : Duration.ofMillis(lifetimeMillis)
            );
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static boolean parseBoolean(String value) {
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw new IllegalArgumentException("Invalid routing profile boolean");
    }

    private InternalRoutingChannels() {}
}
