/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.NodeId;

public final class OverlayPlan {

    private final List<NodeId> nodes;
    private final Set<DesiredDirectEdge> edges;
    private final Map<NodeId, Set<NodeId>> neighbors;

    OverlayPlan(List<NodeId> nodes, Set<DesiredDirectEdge> edges) {
        this.nodes = Collections.unmodifiableList(new ArrayList<NodeId>(nodes));
        this.edges = Collections.unmodifiableSet(new HashSet<DesiredDirectEdge>(edges));
        Map<NodeId, Set<NodeId>> mutable = new HashMap<NodeId, Set<NodeId>>();
        for (NodeId node : nodes) {
            mutable.put(node, new HashSet<NodeId>());
        }
        for (DesiredDirectEdge edge : edges) {
            mutable.get(edge.getFirst()).add(edge.getSecond());
            mutable.get(edge.getSecond()).add(edge.getFirst());
        }
        Map<NodeId, Set<NodeId>> frozen = new HashMap<NodeId, Set<NodeId>>();
        for (Map.Entry<NodeId, Set<NodeId>> entry : mutable.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        this.neighbors = Collections.unmodifiableMap(frozen);
    }

    public List<NodeId> getNodes() {
        return nodes;
    }

    public Set<DesiredDirectEdge> getEdges() {
        return edges;
    }

    public Set<NodeId> getNeighbors(NodeId node) {
        Set<NodeId> result = neighbors.get(node);
        return result == null ? Collections.emptySet() : result;
    }

    public int degree(NodeId node) {
        return getNeighbors(node).size();
    }

    public boolean isConnected() {
        if (nodes.isEmpty()) return true;
        Set<NodeId> visited = new HashSet<NodeId>();
        ArrayDeque<NodeId> queue = new ArrayDeque<NodeId>();
        queue.add(nodes.get(0));
        while (!queue.isEmpty()) {
            NodeId node = queue.removeFirst();
            if (!visited.add(node)) continue;
            queue.addAll(getNeighbors(node));
        }
        return visited.size() == nodes.size();
    }
}
