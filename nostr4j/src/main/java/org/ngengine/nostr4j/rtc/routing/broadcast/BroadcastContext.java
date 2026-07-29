/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import java.nio.ByteBuffer;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.platform.AsyncTask;

public interface BroadcastContext {
    TopologyGraph currentGraph();

    TopologyGraph graphBySnapshotId(String snapshotId);

    AsyncTask<Boolean> sendTreeEdge(NodeId child, RouteTransportProfile profile, ByteBuffer encodedFrame);

    boolean deliverBroadcast(NodeId origin, String logicalChannel, ByteBuffer payload);

    AsyncTask<Boolean> sendAck(BroadcastAck ack);

    AsyncTask<Boolean> repairUnicast(NodeId target, ByteBuffer encodedFrame);
}
