/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import java.util.Objects;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.NodeId;

public final class BroadcastAck {

    private final NodeId origin;
    private final NodeId responder;
    private final CircuitId broadcastId;
    private final String logicalChannel;

    public BroadcastAck(NodeId origin, NodeId responder, CircuitId broadcastId, String logicalChannel) {
        this.origin = Objects.requireNonNull(origin);
        this.responder = Objects.requireNonNull(responder);
        this.broadcastId = Objects.requireNonNull(broadcastId);
        this.logicalChannel = Objects.requireNonNull(logicalChannel);
    }

    public NodeId getOrigin() {
        return origin;
    }

    public NodeId getResponder() {
        return responder;
    }

    public CircuitId getBroadcastId() {
        return broadcastId;
    }

    public String getLogicalChannel() {
        return logicalChannel;
    }
}
