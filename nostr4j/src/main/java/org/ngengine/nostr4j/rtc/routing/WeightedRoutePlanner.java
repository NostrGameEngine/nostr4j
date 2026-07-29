/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;

/**
 * Produces bounded, deterministic, simple-path candidates using a best-first
 * traversal. Recent failures penalize the complete route, not individual peers.
 */
public final class WeightedRoutePlanner {

    private static final Duration DEFAULT_FAILURE_PENALTY_LIFETIME = Duration.ofSeconds(30);

    private final RouteCostModel costs;
    private final int maxHops;
    private final int maxCandidates;
    private final Duration failurePenaltyLifetime;
    private final Map<String, Instant> failedRoutes = new HashMap<String, Instant>();

    public WeightedRoutePlanner() {
        this(
            RouteCostModel.DEFAULT,
            RoutingLimits.MAX_ROUTE_HOPS,
            RoutingLimits.MAX_ROUTE_CANDIDATES,
            DEFAULT_FAILURE_PENALTY_LIFETIME
        );
    }

    public WeightedRoutePlanner(RouteCostModel costs, int maxHops, int maxCandidates, Duration failurePenaltyLifetime) {
        if (maxHops < 1 || maxHops > RoutingLimits.MAX_ROUTE_HOPS) {
            throw new IllegalArgumentException("Invalid maximum route hop count");
        }
        if (maxCandidates < 1 || maxCandidates > RoutingLimits.MAX_ROUTE_CANDIDATES) {
            throw new IllegalArgumentException("Invalid maximum route candidate count");
        }
        this.costs = costs;
        this.maxHops = maxHops;
        this.maxCandidates = maxCandidates;
        this.failurePenaltyLifetime = failurePenaltyLifetime;
    }

    public synchronized List<RoutePath> plan(TopologyGraph graph, NodeId source, NodeId destination, Instant now) {
        return plan(graph, source, destination, null, now);
    }

    public synchronized List<RoutePath> plan(
        TopologyGraph graph,
        NodeId source,
        NodeId destination,
        RoutePath failedPath,
        Instant now
    ) {
        if (source.equals(destination)) {
            return Collections.emptyList();
        }
        if (!graph.getNodes().contains(source) || !graph.getNodes().contains(destination)) {
            return Collections.emptyList();
        }
        expireFailurePenalties(now);
        PriorityQueue<PartialPath> queue = new PriorityQueue<PartialPath>(partialComparator());
        queue.add(PartialPath.start(source));
        List<RoutePath> candidates = new ArrayList<RoutePath>();
        int expansionLimit = Math.max(256, graph.getNodes().size() * maxCandidates * maxHops * 4);
        int expansions = 0;
        while (!queue.isEmpty() && candidates.size() < maxCandidates && expansions++ < expansionLimit) {
            PartialPath current = queue.poll();
            NodeId last = current.last();
            if (last.equals(destination)) {
                RoutePath route = finish(current, failedPath, now);
                candidates.add(route);
                continue;
            }
            if (current.edges.size() >= maxHops) {
                continue;
            }
            for (TopologyEdge edge : graph.getEdges(last)) {
                NodeId next = edge.other(last);
                if (current.seen.contains(next)) {
                    continue;
                }
                queue.add(current.append(next, edge, costs.edgeCost(edge.getEffectiveTransport())));
            }
        }
        candidates.sort(routeComparator(failedPath));
        return Collections.unmodifiableList(candidates);
    }

    public synchronized void recordFailure(RoutePath route, Instant now) {
        expireFailurePenalties(now);
        if (failedRoutes.size() >= RoutingLimits.MAX_FAILED_ROUTE_PENALTIES && !failedRoutes.containsKey(route.fingerprint())) {
            String earliest = failedRoutes
                .entrySet()
                .stream()
                .min(
                    Comparator
                        .comparing((Map.Entry<String, Instant> entry) -> entry.getValue())
                        .thenComparing(Map.Entry::getKey)
                )
                .map(Map.Entry::getKey)
                .orElse(null);
            if (earliest != null) failedRoutes.remove(earliest);
        }
        failedRoutes.put(route.fingerprint(), now.plus(failurePenaltyLifetime));
    }

    public synchronized void clearFailures() {
        failedRoutes.clear();
    }

    synchronized int failedRouteCount() {
        return failedRoutes.size();
    }

    private RoutePath finish(PartialPath path, RoutePath failedPath, Instant now) {
        int penalty = failedRoutes.containsKey(path.fingerprint()) ? costs.getFailedRoutePenalty() : 0;
        if (failedPath != null && path.nodes.equals(failedPath.getNodes())) {
            penalty = Math.addExact(penalty, costs.getFailedRoutePenalty());
        }
        return new RoutePath(path.nodes, path.edges, path.baseCost, Math.addExact(path.baseCost, penalty), path.turnEdges);
    }

    private void expireFailurePenalties(Instant now) {
        failedRoutes.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private static Comparator<PartialPath> partialComparator() {
        return Comparator
            .comparingInt((PartialPath path) -> path.baseCost)
            .thenComparingInt(path -> path.turnEdges)
            .thenComparingInt(path -> path.edges.size())
            .thenComparing(PartialPath::compareNodes);
    }

    private static Comparator<RoutePath> routeComparator(RoutePath failedPath) {
        return Comparator
            .comparingInt(RoutePath::getTotalCost)
            .thenComparingInt(RoutePath::getTurnEdges)
            .thenComparingInt(RoutePath::getHopCount)
            .thenComparingInt(path -> disjointRank(path, failedPath))
            .thenComparing(WeightedRoutePlanner::compareRouteNodes);
    }

    private static int disjointRank(RoutePath candidate, RoutePath failed) {
        if (failed == null) return 0;
        if (candidate.isNodeDisjointFrom(failed)) return 0;
        if (candidate.isEdgeDisjointFrom(failed)) return 1;
        if (!candidate.equals(failed)) return 2;
        return 3;
    }

    private static int compareRouteNodes(RoutePath left, RoutePath right) {
        return compareNodes(left.getNodes(), right.getNodes());
    }

    private static int compareNodes(List<NodeId> left, List<NodeId> right) {
        int count = Math.min(left.size(), right.size());
        for (int index = 0; index < count; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.size(), right.size());
    }

    private static final class PartialPath {

        private final List<NodeId> nodes;
        private final List<TopologyEdge> edges;
        private final Set<NodeId> seen;
        private final int baseCost;
        private final int turnEdges;

        private PartialPath(List<NodeId> nodes, List<TopologyEdge> edges, Set<NodeId> seen, int baseCost, int turnEdges) {
            this.nodes = nodes;
            this.edges = edges;
            this.seen = seen;
            this.baseCost = baseCost;
            this.turnEdges = turnEdges;
        }

        private static PartialPath start(NodeId source) {
            return new PartialPath(
                new ArrayList<NodeId>(List.of(source)),
                new ArrayList<TopologyEdge>(),
                new HashSet<NodeId>(Set.of(source)),
                0,
                0
            );
        }

        private PartialPath append(NodeId node, TopologyEdge edge, int edgeCost) {
            List<NodeId> nextNodes = new ArrayList<NodeId>(nodes);
            nextNodes.add(node);
            List<TopologyEdge> nextEdges = new ArrayList<TopologyEdge>(edges);
            nextEdges.add(edge);
            Set<NodeId> nextSeen = new HashSet<NodeId>(seen);
            nextSeen.add(node);
            int nextTurnEdges = turnEdges + (edge.getEffectiveTransport() == TopologyTransport.TURN ? 1 : 0);
            return new PartialPath(nextNodes, nextEdges, nextSeen, Math.addExact(baseCost, edgeCost), nextTurnEdges);
        }

        private NodeId last() {
            return nodes.get(nodes.size() - 1);
        }

        private String fingerprint() {
            StringBuilder out = new StringBuilder(nodes.size() * NodeId.SIZE * 2);
            for (NodeId node : nodes) {
                out.append(node.asHex());
            }
            return out.toString();
        }

        private int compareNodes(PartialPath other) {
            return WeightedRoutePlanner.compareNodes(nodes, other.nodes);
        }
    }
}
