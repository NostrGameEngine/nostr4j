/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;

final class BroadcastDedupCache {

    private final LinkedHashMap<Key, Instant> seen = new LinkedHashMap<Key, Instant>();

    synchronized boolean markIfNew(NodeId origin, CircuitId id, String channel, Instant expiresAt, Instant now) {
        seen.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        Key key = new Key(origin, id, channel);
        if (seen.containsKey(key)) return false;
        seen.put(key, expiresAt);
        while (seen.size() > RoutingLimits.MAX_BROADCAST_TRACKERS) {
            seen.remove(seen.keySet().iterator().next());
        }
        return true;
    }

    synchronized void clear() {
        seen.clear();
    }

    private static final class Key {

        private final NodeId origin;
        private final CircuitId id;
        private final String channel;

        private Key(NodeId origin, CircuitId id, String channel) {
            this.origin = origin;
            this.id = id;
            this.channel = channel;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return origin.equals(that.origin) && id.equals(that.id) && channel.equals(that.channel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(origin, id, channel);
        }
    }
}
