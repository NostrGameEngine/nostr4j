/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestMutualTopologyGraphBuilder {

    private final NostrKeyPair roomKeys = new NostrKeyPair();
    private final RoutingScope scope = new RoutingScope(roomKeys.getPublicKey(), "graph-proto", "graph-app");
    private final NostrRTCPeer alice = peer("alice");
    private final NostrRTCPeer bob = peer("bob");
    private final NostrRTCPeer carol = peer("carol");
    private final NodeId aliceNode = node(alice);
    private final NodeId bobNode = node(bob);
    private final NodeId carolNode = node(carol);
    private final Instant now = Instant.now();
    private final MutualTopologyGraphBuilder builder = new MutualTopologyGraphBuilder();

    @Test
    public void testOnlyReciprocalMatchingUnexpiredEdgeIsAccepted() {
        EdgeId edge = EdgeId.derive(scope, aliceNode, bobNode);
        TopologySnapshot aliceOnly = snapshot(
            alice,
            1L,
            now.plusSeconds(60),
            List.of(neighbor(bob, edge, TopologyTransport.RTC))
        );
        TopologyGraph unilateral = builder.build(scope, List.of(alice, bob), List.of(aliceOnly), now);
        assertEquals(0, unilateral.getEdges().size());

        EdgeId wrong = EdgeId.derive(scope, aliceNode, carolNode);
        TopologySnapshot bobWrong = snapshot(
            bob,
            1L,
            now.plusSeconds(60),
            List.of(neighbor(alice, wrong, TopologyTransport.RTC))
        );
        TopologyGraph mismatched = builder.build(scope, List.of(alice, bob), List.of(aliceOnly, bobWrong), now);
        assertEquals(0, mismatched.getEdges().size());

        TopologySnapshot bobMatching = snapshot(
            bob,
            2L,
            now.plusSeconds(60),
            List.of(neighbor(alice, edge, TopologyTransport.TURN))
        );
        TopologyGraph reciprocal = builder.build(scope, List.of(alice, bob), List.of(aliceOnly, bobMatching), now);
        assertEquals(1, reciprocal.getEdges().size());
        assertEquals(TopologyTransport.TURN, reciprocal.findEdge(aliceNode, bobNode).getEffectiveTransport());
    }

    @Test
    public void testExpiredEndpointAndMissingPresenceRemoveEdge() {
        EdgeId edge = EdgeId.derive(scope, aliceNode, bobNode);
        TopologySnapshot aliceSnapshot = snapshot(
            alice,
            1L,
            now.plusSeconds(60),
            List.of(neighbor(bob, edge, TopologyTransport.RTC))
        );
        TopologySnapshot bobExpired = snapshot(
            bob,
            1L,
            now.minusSeconds(1),
            List.of(neighbor(alice, edge, TopologyTransport.RTC))
        );

        TopologyGraph expired = builder.build(scope, List.of(alice, bob), List.of(aliceSnapshot, bobExpired), now);
        assertNull(expired.findEdge(aliceNode, bobNode));

        TopologySnapshot bobValid = snapshot(
            bob,
            2L,
            now.plusSeconds(60),
            List.of(neighbor(alice, edge, TopologyTransport.RTC))
        );
        TopologyGraph absent = builder.build(scope, List.of(alice), List.of(aliceSnapshot, bobValid), now);
        assertEquals(1, absent.getNodes().size());
        assertEquals(0, absent.getEdges().size());
    }

    @Test
    public void testComponentsAndRepairCandidatesAreDeterministic() {
        TopologyGraph disconnected = builder.build(scope, List.of(alice, bob, carol), List.of(), now);
        assertEquals(3, disconnected.connectedComponents().size());

        PartitionRepairPlanner planner = new PartitionRepairPlanner();
        List<DesiredDirectEdge> first = planner.plan(scope, disconnected);
        List<DesiredDirectEdge> second = planner.plan(scope, disconnected);
        assertEquals(first, second);
        assertEquals(2, first.size());
        assertEquals(OverlayEdgePriority.REPAIR, first.get(0).getPriority());
    }

    private NostrRTCPeer peer(String session) {
        return new NostrRTCPeer(
            NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
            scope.getApplicationId(),
            scope.getProtocolId(),
            session,
            roomKeys.getPublicKey(),
            null
        );
    }

    private NodeId node(NostrRTCPeer peer) {
        return NodeId.derive(scope, peer.getPubkey(), peer.getSessionId());
    }

    private TopologyNeighbor neighbor(NostrRTCPeer peer, EdgeId edge, TopologyTransport transport) {
        return new TopologyNeighbor(node(peer), peer.getPubkey(), peer.getSessionId(), edge, transport);
    }

    private TopologySnapshot snapshot(NostrRTCPeer peer, long revision, Instant expires, List<TopologyNeighbor> neighbors) {
        Instant issued = expires.isAfter(now) ? now.minusSeconds(1) : expires.minusSeconds(60);
        return new TopologySnapshot(
            scope,
            peer.getPubkey(),
            peer.getSessionId(),
            revision,
            node(peer),
            new NostrKeyPair().getPublicKey(),
            issued,
            expires,
            neighbors
        );
    }
}
