/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.time.Instant;
import java.util.Objects;

public final class CircuitForwardingEntry {

    private final NodeId previousDirectPeer;
    private final CircuitId circuitId;
    private final NodeId nextDirectPeer;
    private final RouteTransportProfile profile;
    private final Instant expiresAt;

    CircuitForwardingEntry(
        NodeId previousDirectPeer,
        CircuitId circuitId,
        NodeId nextDirectPeer,
        RouteTransportProfile profile,
        Instant expiresAt
    ) {
        this.previousDirectPeer = Objects.requireNonNull(previousDirectPeer);
        this.circuitId = Objects.requireNonNull(circuitId);
        this.nextDirectPeer = Objects.requireNonNull(nextDirectPeer);
        this.profile = Objects.requireNonNull(profile);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public NodeId getPreviousDirectPeer() {
        return previousDirectPeer;
    }

    public CircuitId getCircuitId() {
        return circuitId;
    }

    public NodeId getNextDirectPeer() {
        return nextDirectPeer;
    }

    public RouteTransportProfile getProfile() {
        return profile;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
