/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.util.Objects;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;

public final class TopologyEdge {

    private final EdgeId edgeId;
    private final NodeId first;
    private final NodeId second;
    private final TopologyTransport firstToSecond;
    private final TopologyTransport secondToFirst;

    public TopologyEdge(
        EdgeId edgeId,
        NodeId first,
        NodeId second,
        TopologyTransport firstToSecond,
        TopologyTransport secondToFirst
    ) {
        this.edgeId = Objects.requireNonNull(edgeId, "Edge id cannot be null");
        if (first.compareTo(second) <= 0) {
            this.first = first;
            this.second = second;
            this.firstToSecond = firstToSecond;
            this.secondToFirst = secondToFirst;
        } else {
            this.first = second;
            this.second = first;
            this.firstToSecond = secondToFirst;
            this.secondToFirst = firstToSecond;
        }
    }

    public EdgeId getEdgeId() {
        return edgeId;
    }

    public NodeId getFirst() {
        return first;
    }

    public NodeId getSecond() {
        return second;
    }

    public TopologyTransport getTransport(NodeId from, NodeId to) {
        if (first.equals(from) && second.equals(to)) return firstToSecond;
        if (second.equals(from) && first.equals(to)) return secondToFirst;
        return null;
    }

    public TopologyTransport getEffectiveTransport() {
        if (firstToSecond == TopologyTransport.TURN || secondToFirst == TopologyTransport.TURN) {
            return TopologyTransport.TURN;
        }
        if (firstToSecond == TopologyTransport.RTC && secondToFirst == TopologyTransport.RTC) {
            return TopologyTransport.RTC;
        }
        return TopologyTransport.UNKNOWN;
    }

    public NodeId other(NodeId node) {
        if (first.equals(node)) return second;
        if (second.equals(node)) return first;
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TopologyEdge && edgeId.equals(((TopologyEdge) other).edgeId);
    }

    @Override
    public int hashCode() {
        return edgeId.hashCode();
    }
}
