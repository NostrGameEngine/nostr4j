/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class TopologyGraph {

    private static final byte[] SNAPSHOT_DOMAIN = "nip-dc-routing-graph-v1".getBytes(StandardCharsets.UTF_8);
    private final Set<NodeId> nodes;
    private final Set<TopologyEdge> edges;
    private final Map<NodeId, List<TopologyEdge>> adjacency;
    private final String snapshotId;

    public TopologyGraph(Set<NodeId> nodes, Set<TopologyEdge> edges) {
        this.nodes = Collections.unmodifiableSet(new HashSet<NodeId>(nodes));
        this.edges = Collections.unmodifiableSet(new HashSet<TopologyEdge>(edges));
        Map<NodeId, List<TopologyEdge>> mutable = new HashMap<NodeId, List<TopologyEdge>>();
        for (NodeId node : nodes) {
            mutable.put(node, new ArrayList<TopologyEdge>());
        }
        for (TopologyEdge edge : edges) {
            mutable.get(edge.getFirst()).add(edge);
            mutable.get(edge.getSecond()).add(edge);
        }
        Map<NodeId, List<TopologyEdge>> frozen = new HashMap<NodeId, List<TopologyEdge>>();
        for (Map.Entry<NodeId, List<TopologyEdge>> entry : mutable.entrySet()) {
            entry.getValue().sort(Comparator.comparing(edge -> edge.other(entry.getKey())));
            frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.adjacency = Collections.unmodifiableMap(frozen);
        this.snapshotId = computeSnapshotId(nodes, edges);
    }

    public Set<NodeId> getNodes() {
        return nodes;
    }

    public Set<TopologyEdge> getEdges() {
        return edges;
    }

    public List<TopologyEdge> getEdges(NodeId node) {
        List<TopologyEdge> result = adjacency.get(node);
        return result == null ? Collections.emptyList() : result;
    }

    public TopologyEdge findEdge(NodeId first, NodeId second) {
        for (TopologyEdge edge : getEdges(first)) {
            if (second.equals(edge.other(first))) {
                return edge;
            }
        }
        return null;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public List<Set<NodeId>> connectedComponents() {
        List<NodeId> ordered = new ArrayList<NodeId>(nodes);
        Collections.sort(ordered);
        Set<NodeId> visited = new HashSet<NodeId>();
        List<Set<NodeId>> components = new ArrayList<Set<NodeId>>();
        for (NodeId start : ordered) {
            if (visited.contains(start)) continue;
            Set<NodeId> component = new HashSet<NodeId>();
            ArrayDeque<NodeId> queue = new ArrayDeque<NodeId>();
            queue.add(start);
            while (!queue.isEmpty()) {
                NodeId node = queue.removeFirst();
                if (!visited.add(node)) continue;
                component.add(node);
                for (TopologyEdge edge : getEdges(node)) {
                    queue.add(edge.other(node));
                }
            }
            components.add(Collections.unmodifiableSet(component));
        }
        return Collections.unmodifiableList(components);
    }

    private static String computeSnapshotId(Set<NodeId> nodes, Set<TopologyEdge> edges) {
        List<NodeId> sortedNodes = new ArrayList<NodeId>(nodes);
        Collections.sort(sortedNodes);
        List<TopologyEdge> sortedEdges = new ArrayList<TopologyEdge>(edges);
        sortedEdges.sort(Comparator.comparing(edge -> edge.getEdgeId().asHex()));
        int edgeBytes = sortedEdges.size() * (32 + 1);
        ByteBuffer encoded = ByteBuffer.allocate(SNAPSHOT_DOMAIN.length + sortedNodes.size() * NodeId.SIZE + edgeBytes);
        encoded.put(SNAPSHOT_DOMAIN);
        for (NodeId node : sortedNodes) {
            encoded.put(node.toByteArray());
        }
        for (TopologyEdge edge : sortedEdges) {
            encoded.put(NGEUtils.hexToBytes(edge.getEdgeId().asHex()));
            encoded.put((byte) edge.getEffectiveTransport().ordinal());
        }
        return NGEUtils.bytesToHex(NGEPlatform.get().sha256(encoded.array()));
    }
}
