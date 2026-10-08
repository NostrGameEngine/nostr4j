/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.routing.topology.*;
import org.ngengine.platform.NGEUtils;

public class TestRTCStructuralProtection {

    @Test
    public void essentialBridgeIsProtectedWhileCycleEdgesRemainOptional() {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            RoutingScope scope = new RoutingScope(keys.getPublicKey(), "proto", "app");
            List<NodeId> nodes = nodes(4);
            Set<TopologyEdge> edges = new HashSet<>();
            edges.add(edge(scope, nodes.get(0), nodes.get(1)));
            edges.add(edge(scope, nodes.get(1), nodes.get(2)));
            edges.add(edge(scope, nodes.get(2), nodes.get(0)));
            edges.add(edge(scope, nodes.get(2), nodes.get(3)));
            TopologyGraph graph = new TopologyGraph(new HashSet<>(nodes), edges);
            assertTrue(NostrRTCRoom.isBridge(graph, nodes.get(2), nodes.get(3)));
            assertFalse(NostrRTCRoom.isBridge(graph, nodes.get(0), nodes.get(1)));
            assertFalse(NostrRTCRoom.isBridge(graph, nodes.get(0), nodes.get(3)));
        }
    }

    @Test
    public void twoFull16DegreeComponentsAndAnIsolatedPeerStillRequireRepair() {
        try (NostrKeyPair keys = new NostrKeyPair()) {
            RoutingScope scope = new RoutingScope(keys.getPublicKey(), "proto", "app");
            List<NodeId> membership = nodes(35);
            Set<TopologyEdge> edges = new HashSet<>();
            for (int start : new int[] { 0, 17 }) {
                for (int i = start; i < start + 17; i++) {
                    for (int j = i + 1; j < start + 17; j++) edges.add(edge(scope, membership.get(i), membership.get(j)));
                }
            }
            TopologyGraph actual = new TopologyGraph(new HashSet<>(membership), edges);
            assertEquals(3, actual.connectedComponents().size());
            for (int i = 0; i < 34; i++) assertEquals(16, actual.getEdges(membership.get(i)).size());
            assertEquals(0, actual.getEdges(membership.get(34)).size());
            DirectNeighborManager manager = new DirectNeighborManager();
            Instant now = Instant.now();
            manager.update(scope, membership, 16, actual, now);
            OverlayPlan plan = manager.update(scope, membership, 16, actual, now.plusSeconds(6));
            int repairs = 0;
            for (DesiredDirectEdge candidate : plan.getEdges()) {
                if (candidate.getPriority() != OverlayEdgePriority.REPAIR) continue;
                repairs++;
                edges.add(edge(scope, candidate.getFirst(), candidate.getSecond()));
            }
            assertTrue(repairs >= 2);
            for (NodeId member : membership) assertTrue(plan.degree(member) <= 16);
            assertEquals(
                "Planned repairs can reconnect all components",
                1,
                new TopologyGraph(new HashSet<>(membership), edges).connectedComponents().size()
            );
            assertEquals("A plan does not turn desired edges into attested links", 3, actual.connectedComponents().size());
        }
    }

    private static TopologyEdge edge(RoutingScope scope, NodeId a, NodeId b) {
        return new TopologyEdge(EdgeId.derive(scope, a, b), a, b, TopologyTransport.RTC, TopologyTransport.RTC);
    }

    private static List<NodeId> nodes(int count) {
        List<NodeId> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[NodeId.SIZE];
            bytes[NodeId.SIZE - 1] = (byte) i;
            result.add(NodeId.fromHex(NGEUtils.bytesToHex(bytes)));
        }
        return result;
    }
}
