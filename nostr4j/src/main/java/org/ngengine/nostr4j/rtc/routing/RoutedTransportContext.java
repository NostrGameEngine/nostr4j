/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collection;
import org.ngengine.nostr4j.rtc.NostrRTCChannel;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.platform.AsyncTask;

public interface RoutedTransportContext {
    TopologyGraph currentGraph();

    Collection<TopologySnapshot> topologySnapshots(Instant now);

    NodeId destinationFor(NostrRTCChannel channel);

    boolean hasUsableDirectTurn(NostrRTCChannel channel);

    AsyncTask<Boolean> sendToDirectNeighbor(
        NodeId neighbor,
        String internalChannel,
        RouteTransportProfile profile,
        ByteBuffer payload
    );

    boolean deliverNormalFrame(NodeId originalSource, String logicalChannel, ByteBuffer normalFrame);

    void routingStateChanged();
}
