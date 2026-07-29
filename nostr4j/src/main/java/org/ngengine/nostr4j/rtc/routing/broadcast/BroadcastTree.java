/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.NodeId;

public final class BroadcastTree {

    private final NodeId root;
    private final String graphSnapshotId;
    private final Map<NodeId, NodeId> parents;
    private final Map<NodeId, List<NodeId>> children;
    private final Map<NodeId, Integer> depths;

    BroadcastTree(NodeId root, String graphSnapshotId, Map<NodeId, NodeId> parents, Map<NodeId, Integer> depths) {
        this.root = root;
        this.graphSnapshotId = graphSnapshotId;
        this.parents = Collections.unmodifiableMap(new HashMap<NodeId, NodeId>(parents));
        this.depths = Collections.unmodifiableMap(new HashMap<NodeId, Integer>(depths));
        Map<NodeId, List<NodeId>> mutableChildren = new HashMap<NodeId, List<NodeId>>();
        for (NodeId node : depths.keySet()) mutableChildren.put(node, new ArrayList<NodeId>());
        for (Map.Entry<NodeId, NodeId> entry : parents.entrySet()) {
            mutableChildren.get(entry.getValue()).add(entry.getKey());
        }
        Map<NodeId, List<NodeId>> frozen = new HashMap<NodeId, List<NodeId>>();
        for (Map.Entry<NodeId, List<NodeId>> entry : mutableChildren.entrySet()) {
            Collections.sort(entry.getValue());
            frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.children = Collections.unmodifiableMap(frozen);
    }

    public NodeId getRoot() {
        return root;
    }

    public String getGraphSnapshotId() {
        return graphSnapshotId;
    }

    public NodeId getParent(NodeId node) {
        return parents.get(node);
    }

    public List<NodeId> getChildren(NodeId node) {
        List<NodeId> result = children.get(node);
        return result == null ? Collections.emptyList() : result;
    }

    public int getDepth(NodeId node) {
        Integer depth = depths.get(node);
        return depth == null ? -1 : depth.intValue();
    }

    public Set<NodeId> getNodes() {
        return depths.keySet();
    }

    public int edgeCount() {
        return parents.size();
    }
}
