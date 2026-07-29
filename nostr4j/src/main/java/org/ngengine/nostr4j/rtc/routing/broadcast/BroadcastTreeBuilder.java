/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteCostModel;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;

public final class BroadcastTreeBuilder {

    private final RouteCostModel costs;

    public BroadcastTreeBuilder() {
        this(RouteCostModel.DEFAULT);
    }

    public BroadcastTreeBuilder(RouteCostModel costs) {
        this.costs = costs;
    }

    public BroadcastTree build(TopologyGraph graph, NodeId root) {
        if (!graph.getNodes().contains(root)) throw new IllegalArgumentException("Broadcast root is absent from graph");
        Map<NodeId, Best> best = new HashMap<NodeId, Best>();
        PriorityQueue<Best> queue = new PriorityQueue<Best>(
            Comparator
                .comparingInt((Best value) -> value.cost)
                .thenComparingInt(value -> value.turnEdges)
                .thenComparingInt(value -> value.hops)
                .thenComparing(value -> value.node)
                .thenComparing(value -> value.parent, Comparator.nullsFirst(Comparator.naturalOrder()))
        );
        Best initial = new Best(root, null, 0, 0, 0);
        best.put(root, initial);
        queue.add(initial);
        while (!queue.isEmpty()) {
            Best current = queue.poll();
            if (best.get(current.node) != current) continue;
            for (TopologyEdge edge : graph.getEdges(current.node)) {
                NodeId next = edge.other(current.node);
                Best candidate = new Best(
                    next,
                    current.node,
                    current.cost + costs.edgeCost(edge.getEffectiveTransport()),
                    current.turnEdges +
                    (edge.getEffectiveTransport() == org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport.TURN ? 1 : 0),
                    current.hops + 1
                );
                Best previous = best.get(next);
                if (previous == null || candidate.comparePath(previous) < 0) {
                    best.put(next, candidate);
                    queue.add(candidate);
                }
            }
        }
        if (best.size() != graph.getNodes().size()) {
            throw new IllegalArgumentException("Cannot build a broadcast tree for a disconnected graph");
        }
        Map<NodeId, NodeId> parents = new HashMap<NodeId, NodeId>();
        Map<NodeId, Integer> depths = new HashMap<NodeId, Integer>();
        for (Best value : best.values()) {
            if (value.hops > RoutingLimits.MAX_ROUTE_HOPS) {
                throw new IllegalArgumentException("Broadcast tree exceeds maximum hop count");
            }
            depths.put(value.node, Integer.valueOf(value.hops));
            if (value.parent != null) parents.put(value.node, value.parent);
        }
        return new BroadcastTree(root, graph.getSnapshotId(), parents, depths);
    }

    private static final class Best {

        private final NodeId node;
        private final NodeId parent;
        private final int cost;
        private final int turnEdges;
        private final int hops;

        private Best(NodeId node, NodeId parent, int cost, int turnEdges, int hops) {
            this.node = node;
            this.parent = parent;
            this.cost = cost;
            this.turnEdges = turnEdges;
            this.hops = hops;
        }

        private int comparePath(Best other) {
            int comparison = Integer.compare(cost, other.cost);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(turnEdges, other.turnEdges);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(hops, other.hops);
            if (comparison != 0) return comparison;
            if (parent == null) return other.parent == null ? 0 : -1;
            if (other.parent == null) return 1;
            return parent.compareTo(other.parent);
        }
    }
}
