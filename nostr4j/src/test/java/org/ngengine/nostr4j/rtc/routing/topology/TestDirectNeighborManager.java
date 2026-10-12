/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.assertEquals;
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
import org.ngengine.nostr4j.rtc.routing.EdgeId;
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

    @Test
    public void testAnnouncedPeersWithoutPhysicalMembershipDoNotEnterRepairPlan() {
        DirectNeighborManager manager = new DirectNeighborManager(Duration.ZERO, Duration.ZERO);
        List<NodeId> membership = nodes(1);
        OverlayPlan plan = manager.update(scope, membership, 5, emptyGraph(nodes(3)), Instant.now());
        assertEquals(membership, plan.getNodes());
        assertTrue(plan.getEdges().isEmpty());
    }

    @Test
    public void testRepairIgnoresExtraAnnouncedPeersWithoutSkippingAdmittedPartitions() {
        Instant now = Instant.now();
        List<NodeId> membership = nodes(17);
        DirectNeighborManager reference = new DirectNeighborManager(Duration.ZERO, Duration.ZERO);
        OverlayPlan expected = reference.update(scope, membership, 5, emptyGraph(membership), now);
        DirectNeighborManager manager = new DirectNeighborManager(Duration.ZERO, Duration.ZERO);
        OverlayPlan actual = manager.update(scope, membership, 5, emptyGraph(nodes(30)), now);
        assertEquals(expected.getEdges(), actual.getEdges());
        assertTrue(actual.getEdges().stream().anyMatch(edge -> edge.getPriority() == OverlayEdgePriority.REPAIR));
        for (DesiredDirectEdge edge : actual.getEdges()) {
            assertTrue(membership.contains(edge.getFirst()) && membership.contains(edge.getSecond()));
        }
        for (NodeId member : membership) assertTrue(actual.degree(member) <= 5);
    }

    @Test
    public void testMembersMissingFromTopologySnapshotRemainEligibleForRepair() {
        Instant now = Instant.now();
        List<NodeId> membership = nodes(17);
        DirectNeighborManager reference = new DirectNeighborManager(Duration.ZERO, Duration.ZERO);
        OverlayPlan expected = reference.update(scope, membership, 5, emptyGraph(membership), now);
        DirectNeighborManager manager = new DirectNeighborManager(Duration.ZERO, Duration.ZERO);
        OverlayPlan actual = manager.update(scope, membership, 5, emptyGraph(nodes(1)), now);
        assertEquals(expected.getEdges(), actual.getEdges());
        for (NodeId member : membership) assertTrue(actual.degree(member) <= 5);
    }

    @Test
    public void testExcludedAnnouncedBridgeDoesNotHideAdmittedPartitions() {
        List<NodeId> membership = nodes(17);
        NodeId excluded = node(100);
        Set<NodeId> announced = new HashSet<NodeId>(membership);
        announced.add(excluded);
        Set<TopologyEdge> edges = new HashSet<TopologyEdge>();
        for (NodeId member : membership) {
            edges.add(
                new TopologyEdge(
                    EdgeId.derive(scope, member, excluded),
                    member,
                    excluded,
                    TopologyTransport.RTC,
                    TopologyTransport.RTC
                )
            );
        }
        TopologyGraph global = new TopologyGraph(announced, edges);
        assertEquals(1, global.connectedComponents().size());
        String snapshot = global.getSnapshotId();
        DirectNeighborManager manager = new DirectNeighborManager(Duration.ZERO, Duration.ZERO);
        OverlayPlan actual = manager.update(scope, membership, 5, global, Instant.now());
        assertTrue(actual.getEdges().stream().anyMatch(edge -> edge.getPriority() == OverlayEdgePriority.REPAIR));
        for (DesiredDirectEdge edge : actual.getEdges()) {
            assertTrue(membership.contains(edge.getFirst()) && membership.contains(edge.getSecond()));
        }
        for (NodeId member : membership) assertTrue(actual.degree(member) <= 5);
        assertEquals(snapshot, global.getSnapshotId());
        assertEquals(announced, global.getNodes());
        assertEquals(edges, global.getEdges());
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
