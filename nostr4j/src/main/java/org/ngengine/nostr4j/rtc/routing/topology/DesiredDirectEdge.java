/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.util.Objects;
import org.ngengine.nostr4j.rtc.routing.NodeId;

public final class DesiredDirectEdge {

    private final NodeId first;
    private final NodeId second;
    private final OverlayEdgePriority priority;

    public DesiredDirectEdge(NodeId first, NodeId second, OverlayEdgePriority priority) {
        if (first.equals(second)) {
            throw new IllegalArgumentException("Direct edge endpoints must differ");
        }
        this.first = first.compareTo(second) < 0 ? first : second;
        this.second = first.compareTo(second) < 0 ? second : first;
        this.priority = Objects.requireNonNull(priority, "Edge priority cannot be null");
    }

    public NodeId getFirst() {
        return first;
    }

    public NodeId getSecond() {
        return second;
    }

    public OverlayEdgePriority getPriority() {
        return priority;
    }

    public NodeId other(NodeId node) {
        if (first.equals(node)) return second;
        if (second.equals(node)) return first;
        return null;
    }

    public boolean contains(NodeId node) {
        return first.equals(node) || second.equals(node);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DesiredDirectEdge)) return false;
        DesiredDirectEdge that = (DesiredDirectEdge) other;
        return first.equals(that.first) && second.equals(that.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }
}
