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

final class DestinationCircuitTable implements Closeable {

    private final Map<Key, Entry> entries = new HashMap<Key, Entry>();

    synchronized void install(
        NodeId previous,
        CircuitId circuit,
        NodeId source,
        RouteTransportProfile profile,
        Instant expiresAt,
        Instant now
    ) {
        cleanup(now);
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("Destination circuit is expired");
        if (entries.size() >= RoutingLimits.MAX_ACTIVE_CIRCUITS && !entries.containsKey(new Key(previous, circuit))) {
            throw new IllegalStateException("Destination circuit limit exceeded");
        }
        int perNeighbor = 0;
        for (Entry entry : entries.values()) {
            if (entry.previous.equals(previous)) perNeighbor++;
        }
        if (perNeighbor >= RoutingLimits.MAX_ACTIVE_CIRCUITS_PER_NEIGHBOR && !entries.containsKey(new Key(previous, circuit))) {
            throw new IllegalStateException("Destination per-neighbor circuit limit exceeded");
        }
        entries.put(new Key(previous, circuit), new Entry(previous, circuit, source, profile, expiresAt));
    }

    synchronized Entry find(NodeId previous, CircuitId circuit, Instant now) {
        Key key = new Key(previous, circuit);
        Entry entry = entries.get(key);
        if (entry != null && !entry.expiresAt.isAfter(now)) {
            entries.remove(key);
            return null;
        }
        return entry;
    }

    synchronized int cleanup(Instant now) {
        int before = entries.size();
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
        return before - entries.size();
    }

    @Override
    public synchronized void close() {
        entries.clear();
    }

    static final class Entry {

        private final NodeId previous;
        private final CircuitId circuit;
        private final NodeId source;
        private final RouteTransportProfile profile;
        private final Instant expiresAt;

        private Entry(NodeId previous, CircuitId circuit, NodeId source, RouteTransportProfile profile, Instant expiresAt) {
            this.previous = previous;
            this.circuit = circuit;
            this.source = source;
            this.profile = profile;
            this.expiresAt = expiresAt;
        }

        NodeId getSource() {
            return source;
        }

        RouteTransportProfile getProfile() {
            return profile;
        }
    }

    private static final class Key {

        private final NodeId previous;
        private final CircuitId circuit;

        private Key(NodeId previous, CircuitId circuit) {
            this.previous = Objects.requireNonNull(previous);
            this.circuit = Objects.requireNonNull(circuit);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return previous.equals(that.previous) && circuit.equals(that.circuit);
        }

        @Override
        public int hashCode() {
            return Objects.hash(previous, circuit);
        }
    }
}
