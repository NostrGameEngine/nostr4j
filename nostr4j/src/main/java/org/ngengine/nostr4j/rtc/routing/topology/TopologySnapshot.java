/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;

public final class TopologySnapshot {

    private final RoutingScope scope;
    private final NostrPublicKey peerPubkey;
    private final String sessionId;
    private final long revision;
    private final NodeId nodeId;
    private final NostrPublicKey routingPublicKey;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final List<TopologyNeighbor> neighbors;

    public TopologySnapshot(
        RoutingScope scope,
        NostrPublicKey peerPubkey,
        String sessionId,
        long revision,
        NodeId nodeId,
        NostrPublicKey routingPublicKey,
        Instant issuedAt,
        Instant expiresAt,
        List<TopologyNeighbor> neighbors
    ) {
        if (revision <= 0L) {
            throw new IllegalArgumentException("Topology revision must be positive");
        }
        Objects.requireNonNull(neighbors, "Topology neighbors cannot be null");
        if (neighbors.size() > RoutingLimits.MAX_TOPOLOGY_NEIGHBORS) {
            throw new IllegalArgumentException("Topology neighbor limit exceeded");
        }
        this.scope = Objects.requireNonNull(scope, "Routing scope cannot be null");
        this.peerPubkey = Objects.requireNonNull(peerPubkey, "Peer pubkey cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "Session id cannot be null");
        if (sessionId.isEmpty()) {
            throw new IllegalArgumentException("Session id cannot be empty");
        }
        this.revision = revision;
        this.nodeId = Objects.requireNonNull(nodeId, "NodeId cannot be null");
        this.routingPublicKey = Objects.requireNonNull(routingPublicKey, "Routing public key cannot be null");
        this.issuedAt = Objects.requireNonNull(issuedAt, "Topology issue time cannot be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "Topology expiry cannot be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Topology expiry must be after issue time");
        }
        if (expiresAt.isAfter(issuedAt.plusSeconds(RoutingLimits.MAX_TOPOLOGY_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Topology lifetime exceeds limit");
        }
        Set<NodeId> seen = new HashSet<NodeId>();
        for (TopologyNeighbor neighbor : neighbors) {
            Objects.requireNonNull(neighbor, "Topology neighbor cannot be null");
            if (nodeId.equals(neighbor.getNodeId())) {
                throw new IllegalArgumentException("Topology snapshot cannot list itself as a neighbor");
            }
            if (!seen.add(neighbor.getNodeId())) {
                throw new IllegalArgumentException("Duplicate topology neighbor");
            }
        }
        this.neighbors = Collections.unmodifiableList(new ArrayList<TopologyNeighbor>(neighbors));
    }

    public RoutingScope getScope() {
        return scope;
    }

    public NostrPublicKey getPeerPubkey() {
        return peerPubkey;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getRevision() {
        return revision;
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public NostrPublicKey getRoutingPublicKey() {
        return routingPublicKey;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<TopologyNeighbor> getNeighbors() {
        return neighbors;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
