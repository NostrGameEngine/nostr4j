/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;

public final class RouteTransportProfile {

    public static final RouteTransportProfile RELIABLE_ORDERED = new RouteTransportProfile(true, true, null, null);
    public static final RouteTransportProfile UNRELIABLE_UNORDERED = new RouteTransportProfile(
        false,
        false,
        Integer.valueOf(0),
        null
    );

    private final boolean ordered;
    private final boolean reliable;
    private final Integer maxRetransmits;
    private final Duration maxPacketLifeTime;

    public RouteTransportProfile(
        boolean ordered,
        boolean reliable,
        @Nullable Integer maxRetransmits,
        @Nullable Duration maxPacketLifeTime
    ) {
        if (maxRetransmits != null && maxRetransmits.intValue() < 0) {
            throw new IllegalArgumentException("maxRetransmits cannot be negative");
        }
        if (maxPacketLifeTime != null && (maxPacketLifeTime.isNegative() || maxPacketLifeTime.isZero())) {
            throw new IllegalArgumentException("maxPacketLifeTime must be positive");
        }
        if (maxRetransmits != null && maxPacketLifeTime != null) {
            throw new IllegalArgumentException("Only one partial reliability limit can be configured");
        }
        this.ordered = ordered;
        this.reliable = reliable;
        this.maxRetransmits = maxRetransmits;
        this.maxPacketLifeTime = maxPacketLifeTime;
    }

    public boolean isOrdered() {
        return ordered;
    }

    public boolean isReliable() {
        return reliable;
    }

    @Nullable
    public Integer getMaxRetransmits() {
        return maxRetransmits;
    }

    @Nullable
    public Duration getMaxPacketLifeTime() {
        return maxPacketLifeTime;
    }

    public boolean requiresDestinationAck() {
        return reliable || (maxRetransmits != null && maxRetransmits.intValue() > 0) || maxPacketLifeTime != null;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RouteTransportProfile)) return false;
        RouteTransportProfile that = (RouteTransportProfile) other;
        return (
            ordered == that.ordered &&
            reliable == that.reliable &&
            Objects.equals(maxRetransmits, that.maxRetransmits) &&
            Objects.equals(maxPacketLifeTime, that.maxPacketLifeTime)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(ordered, reliable, maxRetransmits, maxPacketLifeTime);
    }
}
