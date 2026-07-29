/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.platform.NGEUtils;

public class TestDirectNeighborManager {

    private final RoutingScope scope = new RoutingScope(new NostrKeyPair().getPublicKey(), "manager-proto", "manager-app");

    @Test
    public void testBriefMembershipChurnRetainsHealthyOptionalEdges() {
        DirectNeighborManager manager = new DirectNeighborManager(Duration.ofHours(1), Duration.ofMinutes(1));
        Instant now = Instant.now();
        List<NodeId> original = nodes(17);
        OverlayPlan first = manager.update(scope, original, 6, emptyGraph(original), now);
        Set<DesiredDirectEdge> oldChords = chords(first);
        assertFalse(oldChords.isEmpty());

        List<NodeId> changed = new ArrayList<NodeId>(original);
        changed.add(node(100));
        OverlayPlan second = manager.update(scope, changed, 6, emptyGraph(changed), now.plusSeconds(1));
        assertTrue(chords(second).stream().anyMatch(oldChords::contains));
        for (NodeId member : changed) {
            assertTrue(second.degree(member) <= 6);
        }
    }

    @Test
    public void testRepairDisplacesChordWithoutBreakingDegreeCap() {
        DirectNeighborManager manager = new DirectNeighborManager(Duration.ZERO, Duration.ZERO);
        Instant now = Instant.now();
        List<NodeId> membership = nodes(17);
        TopologyGraph partitioned = emptyGraph(membership);
        OverlayPlan plan = manager.update(scope, membership, 5, partitioned, now);
        assertTrue(plan.getEdges().stream().anyMatch(edge -> edge.getPriority() == OverlayEdgePriority.REPAIR));
        for (NodeId member : membership) {
            assertTrue(plan.degree(member) <= 5);
        }
    }

    private static Set<DesiredDirectEdge> chords(OverlayPlan plan) {
        Set<DesiredDirectEdge> result = new HashSet<DesiredDirectEdge>();
        for (DesiredDirectEdge edge : plan.getEdges()) {
            if (edge.getPriority() == OverlayEdgePriority.CHORD) result.add(edge);
        }
        return result;
    }

    private static TopologyGraph emptyGraph(List<NodeId> nodes) {
        return new TopologyGraph(new HashSet<NodeId>(nodes), Collections.emptySet());
    }

    private static List<NodeId> nodes(int count) {
        List<NodeId> result = new ArrayList<NodeId>();
        for (int index = 0; index < count; index++) result.add(node(index));
        return result;
    }

    private static NodeId node(int value) {
        byte[] bytes = new byte[NodeId.SIZE];
        bytes[NodeId.SIZE - 2] = (byte) (value >>> 8);
        bytes[NodeId.SIZE - 1] = (byte) value;
        return NodeId.fromHex(NGEUtils.bytesToHex(bytes));
    }
}
