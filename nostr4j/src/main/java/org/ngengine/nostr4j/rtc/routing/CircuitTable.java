/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.io.Closeable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded forwarding state scoped by the incoming direct peer and circuit id.
 */
public final class CircuitTable implements Closeable {

    private final int globalLimit;
    private final int perNeighborLimit;
    private final Map<Key, CircuitForwardingEntry> entries = new HashMap<Key, CircuitForwardingEntry>();
    private boolean closed;

    public CircuitTable() {
        this(RoutingLimits.MAX_ACTIVE_CIRCUITS, RoutingLimits.MAX_ACTIVE_CIRCUITS_PER_NEIGHBOR);
    }

    CircuitTable(int globalLimit, int perNeighborLimit) {
        if (
            globalLimit < 1 ||
            globalLimit > RoutingLimits.MAX_ACTIVE_CIRCUITS ||
            perNeighborLimit < 1 ||
            perNeighborLimit > RoutingLimits.MAX_ACTIVE_CIRCUITS_PER_NEIGHBOR
        ) {
            throw new IllegalArgumentException("Invalid circuit table limits");
        }
        this.globalLimit = globalLimit;
        this.perNeighborLimit = perNeighborLimit;
    }

    public synchronized CircuitForwardingEntry install(
        NodeId previousDirectPeer,
        CircuitId circuitId,
        NodeId nextDirectPeer,
        RouteTransportProfile profile,
        Instant expiresAt,
        Instant now
    ) {
        ensureOpen();
        cleanup(now);
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("Circuit is already expired");
        if (expiresAt.isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Circuit lifetime exceeds limit");
        }
        Key key = new Key(previousDirectPeer, circuitId);
        CircuitForwardingEntry existing = entries.get(key);
        if (existing == null) {
            if (entries.size() >= globalLimit) throw new IllegalStateException("Global circuit limit exceeded");
            if (countFor(previousDirectPeer) >= perNeighborLimit) {
                throw new IllegalStateException("Per-neighbor circuit limit exceeded");
            }
        }
        CircuitForwardingEntry entry = new CircuitForwardingEntry(
            previousDirectPeer,
            circuitId,
            nextDirectPeer,
            profile,
            expiresAt
        );
        entries.put(key, entry);
        return entry;
    }

    public synchronized CircuitForwardingEntry find(NodeId previousDirectPeer, CircuitId circuitId, Instant now) {
        ensureOpen();
        Key key = new Key(previousDirectPeer, circuitId);
        CircuitForwardingEntry entry = entries.get(key);
        if (entry != null && entry.isExpired(now)) {
            entries.remove(key);
            return null;
        }
        return entry;
    }

    public synchronized boolean remove(NodeId previousDirectPeer, CircuitId circuitId) {
        return entries.remove(new Key(previousDirectPeer, circuitId)) != null;
    }

    public synchronized int cleanup(Instant now) {
        int before = entries.size();
        entries.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        return before - entries.size();
    }

    public synchronized int size() {
        return entries.size();
    }

    private int countFor(NodeId previousDirectPeer) {
        int count = 0;
        for (CircuitForwardingEntry entry : entries.values()) {
            if (previousDirectPeer.equals(entry.getPreviousDirectPeer())) count++;
        }
        return count;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Circuit table is closed");
    }

    @Override
    public synchronized void close() {
        closed = true;
        entries.clear();
    }

    private static final class Key {

        private final NodeId previous;
        private final CircuitId circuitId;

        private Key(NodeId previous, CircuitId circuitId) {
            this.previous = Objects.requireNonNull(previous);
            this.circuitId = Objects.requireNonNull(circuitId);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return previous.equals(that.previous) && circuitId.equals(that.circuitId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(previous, circuitId);
        }
    }
}
