/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.util.Objects;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;

public final class TopologyNeighbor {

    private final NodeId nodeId;
    private final NostrPublicKey pubkey;
    private final String sessionId;
    private final EdgeId edgeId;
    private final TopologyTransport transport;

    public TopologyNeighbor(
        NodeId nodeId,
        NostrPublicKey pubkey,
        String sessionId,
        EdgeId edgeId,
        TopologyTransport transport
    ) {
        this.nodeId = Objects.requireNonNull(nodeId, "Neighbor NodeId cannot be null");
        this.pubkey = Objects.requireNonNull(pubkey, "Neighbor pubkey cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "Neighbor session cannot be null");
        this.edgeId = Objects.requireNonNull(edgeId, "Neighbor edge id cannot be null");
        this.transport = Objects.requireNonNull(transport, "Neighbor transport cannot be null");
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public NostrPublicKey getPubkey() {
        return pubkey;
    }

    public String getSessionId() {
        return sessionId;
    }

    public EdgeId getEdgeId() {
        return edgeId;
    }

    public TopologyTransport getTransport() {
        return transport;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TopologyNeighbor)) return false;
        TopologyNeighbor that = (TopologyNeighbor) other;
        return (
            nodeId.equals(that.nodeId) &&
            pubkey.equals(that.pubkey) &&
            sessionId.equals(that.sessionId) &&
            edgeId.equals(that.edgeId) &&
            transport == that.transport
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, pubkey, sessionId, edgeId, transport);
    }
}
