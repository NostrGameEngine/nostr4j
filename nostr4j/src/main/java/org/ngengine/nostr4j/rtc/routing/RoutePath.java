/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;

public final class RoutePath {

    private final List<NodeId> nodes;
    private final List<TopologyEdge> edges;
    private final int baseCost;
    private final int totalCost;
    private final int turnEdges;

    RoutePath(List<NodeId> nodes, List<TopologyEdge> edges, int baseCost, int totalCost, int turnEdges) {
        this.nodes = Collections.unmodifiableList(new ArrayList<NodeId>(nodes));
        this.edges = Collections.unmodifiableList(new ArrayList<TopologyEdge>(edges));
        this.baseCost = baseCost;
        this.totalCost = totalCost;
        this.turnEdges = turnEdges;
    }

    public List<NodeId> getNodes() {
        return nodes;
    }

    public List<TopologyEdge> getEdges() {
        return edges;
    }

    public int getBaseCost() {
        return baseCost;
    }

    public int getTotalCost() {
        return totalCost;
    }

    public int getTurnEdges() {
        return turnEdges;
    }

    public int getHopCount() {
        return edges.size();
    }

    public NodeId getSource() {
        return nodes.get(0);
    }

    public NodeId getDestination() {
        return nodes.get(nodes.size() - 1);
    }

    public boolean isNodeDisjointFrom(RoutePath other) {
        Set<NodeId> intermediates = intermediateNodes(other);
        for (int index = 1; index + 1 < nodes.size(); index++) {
            if (intermediates.contains(nodes.get(index))) {
                return false;
            }
        }
        return true;
    }

    public boolean isEdgeDisjointFrom(RoutePath other) {
        Set<EdgeId> otherEdges = new HashSet<EdgeId>();
        for (TopologyEdge edge : other.edges) {
            otherEdges.add(edge.getEdgeId());
        }
        for (TopologyEdge edge : edges) {
            if (otherEdges.contains(edge.getEdgeId())) {
                return false;
            }
        }
        return true;
    }

    private static Set<NodeId> intermediateNodes(RoutePath path) {
        Set<NodeId> nodes = new HashSet<NodeId>();
        for (int index = 1; index + 1 < path.nodes.size(); index++) {
            nodes.add(path.nodes.get(index));
        }
        return nodes;
    }

    String fingerprint() {
        StringBuilder out = new StringBuilder(nodes.size() * NodeId.SIZE * 2);
        for (NodeId node : nodes) {
            out.append(node.asHex());
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RoutePath && nodes.equals(((RoutePath) other).nodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes);
    }

    @Override
    public String toString() {
        return "RoutePath{hops=" + getHopCount() + ", cost=" + totalCost + ", turnEdges=" + turnEdges + '}';
    }
}
