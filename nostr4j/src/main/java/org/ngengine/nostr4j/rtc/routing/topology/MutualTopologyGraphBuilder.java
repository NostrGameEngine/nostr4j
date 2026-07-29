/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;

public final class MutualTopologyGraphBuilder {

    public TopologyGraph build(
        RoutingScope scope,
        Collection<NostrRTCPeer> activePeers,
        Collection<TopologySnapshot> snapshots,
        Instant now
    ) {
        Map<NodeId, NostrRTCPeer> active = new HashMap<NodeId, NostrRTCPeer>();
        for (NostrRTCPeer peer : activePeers) {
            if (
                peer == null ||
                peer.getPubkey() == null ||
                !scope.getRoomPubkey().equals(peer.getRoomPubkey()) ||
                !scope.getProtocolId().equals(peer.getProtocolId()) ||
                !scope.getApplicationId().equals(peer.getApplicationId())
            ) {
                continue;
            }
            NodeId node = NodeId.derive(scope, peer.getPubkey(), peer.getSessionId());
            active.put(node, peer);
        }

        Map<NodeId, TopologySnapshot> newest = new HashMap<NodeId, TopologySnapshot>();
        for (TopologySnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.isExpired(now) || !scope.equals(snapshot.getScope())) {
                continue;
            }
            NostrRTCPeer peer = active.get(snapshot.getNodeId());
            if (
                peer == null ||
                !peer.getPubkey().equals(snapshot.getPeerPubkey()) ||
                !peer.getSessionId().equals(snapshot.getSessionId())
            ) {
                continue;
            }
            TopologySnapshot previous = newest.get(snapshot.getNodeId());
            if (
                previous == null ||
                snapshot.getRevision() > previous.getRevision() ||
                (snapshot.getRevision() == previous.getRevision() && snapshot.getIssuedAt().isAfter(previous.getIssuedAt()))
            ) {
                newest.put(snapshot.getNodeId(), snapshot);
            }
        }

        Set<TopologyEdge> edges = new HashSet<TopologyEdge>();
        for (TopologySnapshot firstSnapshot : newest.values()) {
            NodeId first = firstSnapshot.getNodeId();
            for (TopologyNeighbor firstClaim : firstSnapshot.getNeighbors()) {
                NodeId second = firstClaim.getNodeId();
                if (first.compareTo(second) >= 0 || !active.containsKey(second)) {
                    continue;
                }
                TopologySnapshot secondSnapshot = newest.get(second);
                if (secondSnapshot == null) {
                    continue;
                }
                TopologyNeighbor secondClaim = findNeighbor(secondSnapshot, first);
                if (secondClaim == null) {
                    continue;
                }
                EdgeId expected = EdgeId.derive(scope, first, second);
                if (!expected.equals(firstClaim.getEdgeId()) || !expected.equals(secondClaim.getEdgeId())) {
                    continue;
                }
                if (
                    !firstSnapshot.getPeerPubkey().equals(secondClaim.getPubkey()) ||
                    !firstSnapshot.getSessionId().equals(secondClaim.getSessionId()) ||
                    !secondSnapshot.getPeerPubkey().equals(firstClaim.getPubkey()) ||
                    !secondSnapshot.getSessionId().equals(firstClaim.getSessionId())
                ) {
                    continue;
                }
                edges.add(new TopologyEdge(expected, first, second, firstClaim.getTransport(), secondClaim.getTransport()));
            }
        }
        return new TopologyGraph(Collections.unmodifiableSet(active.keySet()), edges);
    }

    private static TopologyNeighbor findNeighbor(TopologySnapshot snapshot, NodeId node) {
        for (TopologyNeighbor neighbor : snapshot.getNeighbors()) {
            if (node.equals(neighbor.getNodeId())) {
                return neighbor;
            }
        }
        return null;
    }
}
