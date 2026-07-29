/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.platform.NGEUtils;

public class TestWeightedRoutePlanner {

    private final RoutingScope scope = new RoutingScope(new NostrKeyPair().getPublicKey(), "route-proto", "route-app");
    private final Instant now = Instant.now();

    @Test
    public void testDirectRtcWinsAndTurnIsPenalized() {
        NodeId a = node(1);
        NodeId b = node(2);
        NodeId c = node(3);
        TopologyGraph graph = graph(
            List.of(edge(a, b, TopologyTransport.RTC), edge(a, c, TopologyTransport.TURN), edge(b, c, TopologyTransport.TURN))
        );
        RoutePath directRtc = new WeightedRoutePlanner().plan(graph, a, b, now).get(0);
        assertEquals(List.of(a, b), directRtc.getNodes());
        assertEquals(0, directRtc.getTurnEdges());

        RoutePath directTurn = new WeightedRoutePlanner().plan(graph, a, c, now).get(0);
        assertEquals(List.of(a, c), directTurn.getNodes());
        assertEquals(1, directTurn.getTurnEdges());
    }

    @Test
    public void testShortRtcRouteBeatsTurnButExcessiveRtcRouteDoesNot() {
        NodeId a = node(10);
        NodeId b = node(11);
        NodeId c = node(12);
        TopologyGraph shortGraph = graph(
            List.of(edge(a, c, TopologyTransport.TURN), edge(a, b, TopologyTransport.RTC), edge(b, c, TopologyTransport.RTC))
        );
        assertEquals(List.of(a, b, c), new WeightedRoutePlanner().plan(shortGraph, a, c, now).get(0).getNodes());

        NodeId d = node(13);
        TopologyGraph longGraph = graph(
            List.of(
                edge(a, d, TopologyTransport.TURN),
                edge(a, b, TopologyTransport.RTC),
                edge(b, c, TopologyTransport.RTC),
                edge(c, d, TopologyTransport.RTC)
            )
        );
        assertEquals(List.of(a, d), new WeightedRoutePlanner().plan(longGraph, a, d, now).get(0).getNodes());
    }

    @Test
    public void testCandidatesIncludeDisjointAlternativeAndFailureSelectsIt() {
        NodeId a = node(20);
        NodeId b = node(21);
        NodeId c = node(22);
        NodeId d = node(23);
        NodeId e = node(24);
        TopologyGraph graph = graph(
            List.of(
                edge(a, b, TopologyTransport.RTC),
                edge(b, d, TopologyTransport.RTC),
                edge(a, c, TopologyTransport.RTC),
                edge(c, d, TopologyTransport.RTC),
                edge(a, e, TopologyTransport.RTC),
                edge(e, b, TopologyTransport.RTC)
            )
        );
        WeightedRoutePlanner planner = new WeightedRoutePlanner();
        List<RoutePath> candidates = planner.plan(graph, a, d, now);
        assertTrue(candidates.size() >= 2);
        RoutePath failed = candidates.get(0);
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.isNodeDisjointFrom(failed)));

        planner.recordFailure(failed, now);
        List<RoutePath> retry = planner.plan(graph, a, d, failed, now.plusMillis(1));
        assertFalse(retry.get(0).equals(failed));
        assertTrue(retry.get(0).isNodeDisjointFrom(failed));
    }

    @Test
    public void testHopLimitIsEnforced() {
        List<TopologyEdge> edges = new ArrayList<TopologyEdge>();
        NodeId first = node(40);
        NodeId previous = first;
        for (int index = 1; index <= RoutingLimits.MAX_ROUTE_HOPS + 1; index++) {
            NodeId next = node(40 + index);
            edges.add(edge(previous, next, TopologyTransport.RTC));
            previous = next;
        }
        assertTrue(new WeightedRoutePlanner().plan(graph(edges), first, previous, now).isEmpty());
    }

    @Test
    public void testFailurePenaltyCacheIsCapacityBounded() {
        WeightedRoutePlanner planner = new WeightedRoutePlanner();
        NodeId source = node(1);
        for (int index = 0; index < RoutingLimits.MAX_FAILED_ROUTE_PENALTIES + 17; index++) {
            planner.recordFailure(new RoutePath(List.of(source, node(index + 2)), List.of(), 0, 0, 0), now.plusMillis(index));
        }
        assertEquals(RoutingLimits.MAX_FAILED_ROUTE_PENALTIES, planner.failedRouteCount());
    }

    private TopologyGraph graph(List<TopologyEdge> edges) {
        Set<NodeId> nodes = new HashSet<NodeId>();
        for (TopologyEdge edge : edges) {
            nodes.add(edge.getFirst());
            nodes.add(edge.getSecond());
        }
        return new TopologyGraph(nodes, new HashSet<TopologyEdge>(edges));
    }

    private TopologyEdge edge(NodeId first, NodeId second, TopologyTransport transport) {
        return new TopologyEdge(EdgeId.derive(scope, first, second), first, second, transport, transport);
    }

    private static NodeId node(int value) {
        byte[] bytes = new byte[NodeId.SIZE];
        bytes[NodeId.SIZE - 2] = (byte) (value >>> 8);
        bytes[NodeId.SIZE - 1] = (byte) value;
        return NodeId.fromHex(NGEUtils.bytesToHex(bytes));
    }
}
