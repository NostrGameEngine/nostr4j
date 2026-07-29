/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;

/**
 * Bounded newest-snapshot repository. A replacement must strictly increase its
 * monotonic revision; a same-revision event never replaces existing state.
 */
public final class TopologySnapshotStore {

    private final int capacity;
    private final Map<NodeId, TopologySnapshot> newest = new HashMap<NodeId, TopologySnapshot>();

    public TopologySnapshotStore() {
        this(RoutingLimits.MAX_TOPOLOGY_SNAPSHOTS);
    }

    TopologySnapshotStore(int capacity) {
        if (capacity < 1 || capacity > RoutingLimits.MAX_TOPOLOGY_SNAPSHOTS) {
            throw new IllegalArgumentException("Invalid topology snapshot capacity");
        }
        this.capacity = capacity;
    }

    public synchronized boolean accept(TopologySnapshot snapshot, Instant now) {
        removeExpired(now);
        if (snapshot.isExpired(now)) {
            return false;
        }
        TopologySnapshot previous = newest.get(snapshot.getNodeId());
        if (previous != null && Long.compareUnsigned(snapshot.getRevision(), previous.getRevision()) <= 0) {
            return false;
        }
        if (previous == null && newest.size() >= capacity) {
            NodeId oldest = newest
                .entrySet()
                .stream()
                .min(
                    Comparator
                        .comparing((Map.Entry<NodeId, TopologySnapshot> entry) -> entry.getValue().getExpiresAt())
                        .thenComparing(Map.Entry::getKey)
                )
                .map(Map.Entry::getKey)
                .orElse(null);
            if (oldest != null) newest.remove(oldest);
        }
        newest.put(snapshot.getNodeId(), snapshot);
        return true;
    }

    public synchronized Collection<TopologySnapshot> snapshots(Instant now) {
        removeExpired(now);
        return List.copyOf(newest.values());
    }

    public synchronized boolean removeExpired(Instant now) {
        int before = newest.size();
        newest.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        return newest.size() != before;
    }

    public synchronized int size() {
        return newest.size();
    }

    public synchronized void clear() {
        newest.clear();
    }
}
