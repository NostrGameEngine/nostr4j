/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrRTCChannel;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastAck;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastContext;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastEngine;
import org.ngengine.nostr4j.rtc.routing.topology.BoundedOverlaySelector;
import org.ngengine.nostr4j.rtc.routing.topology.DesiredDirectEdge;
import org.ngengine.nostr4j.rtc.routing.topology.OverlayPlan;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class TestRoutedTransport64Peers {

    @Test
    public void testSendAndBroadcastAcross64PeerBoundedOverlay() throws Exception {
        Network network = new Network(64);
        try {
            NodeId source = network.nodes.get(0);
            long packetId = 1L;
            int routedSends = 0;
            for (int index = 1; index < network.nodes.size(); index++) {
                NodeId destination = network.nodes.get(index);
                if (network.graph.findEdge(source, destination) != null) {
                    network.deliveries.get(destination).incrementAndGet();
                } else {
                    Boolean delivered;
                    try {
                        delivered =
                            network.routed
                                .get(source)
                                .sendRouted(
                                    destination,
                                    "default",
                                    RouteTransportProfile.RELIABLE_ORDERED,
                                    normalFrame(packetId++, index),
                                    Instant.now()
                                )
                                .await();
                    } catch (Throwable error) {
                        throw new AssertionError("Failed routed send to peer index " + index, error);
                    }
                    assertTrue(delivered.booleanValue());
                    routedSends++;
                }
            }
            int unicastDeliveries = network.deliveries
                .entrySet()
                .stream()
                .filter(entry -> !entry.getKey().equals(source))
                .mapToInt(entry -> entry.getValue().get())
                .sum();
            assertEquals(63, unicastDeliveries);
            assertEquals(routedSends, network.routed.get(source).getPayloadEncryptionCount());

            for (AtomicInteger delivery : network.deliveries.values()) delivery.set(0);
            Boolean broadcast = network.broadcast
                .get(source)
                .broadcast(
                    "default",
                    RouteTransportProfile.RELIABLE_ORDERED,
                    ByteBuffer.wrap(new byte[] { 6, 4 }),
                    Instant.now()
                )
                .await();
            assertTrue(broadcast.booleanValue());
            assertEquals(63, network.broadcastForwards.get());
            assertEquals(63, network.broadcastAcks.get());
            for (NodeId node : network.nodes) {
                assertEquals(node.equals(source) ? 0 : 1, network.deliveries.get(node).get());
            }
        } finally {
            network.close();
        }
    }

    private static ByteBuffer normalFrame(long packetId, int value) {
        ByteBuffer frame = ByteBuffer.allocate(16);
        frame.putLong(packetId);
        frame.putShort((short) 0);
        frame.putShort((short) 1);
        frame.putInt(value);
        frame.flip();
        return frame.asReadOnlyBuffer();
    }

    private static final class Network {

        private final RoutingScope scope = new RoutingScope(new NostrKeyPair().getPublicKey(), "scale-proto", "scale-app");
        private final List<NodeId> nodes = new ArrayList<NodeId>();
        private final Map<NodeId, NostrKeyPair> keys = new HashMap<NodeId, NostrKeyPair>();
        private final Map<NodeId, RoutedTransportEngine> routed = new HashMap<NodeId, RoutedTransportEngine>();
        private final Map<NodeId, BroadcastEngine> broadcast = new HashMap<NodeId, BroadcastEngine>();
        private final Map<NodeId, AtomicInteger> deliveries = new HashMap<NodeId, AtomicInteger>();
        private final AtomicInteger broadcastForwards = new AtomicInteger();
        private final AtomicInteger broadcastAcks = new AtomicInteger();
        private final TopologyGraph graph;
        private final Collection<TopologySnapshot> snapshots;

        private Network(int size) {
            for (int index = 0; index < size; index++) {
                int nodeValue = index + 1;
                byte[] bytes = new byte[NodeId.SIZE];
                bytes[NodeId.SIZE - 2] = (byte) (nodeValue >>> 8);
                bytes[NodeId.SIZE - 1] = (byte) nodeValue;
                NodeId node = NodeId.fromHex(NGEUtils.bytesToHex(bytes));
                nodes.add(node);
                keys.put(node, new NostrKeyPair());
                deliveries.put(node, new AtomicInteger());
            }
            OverlayPlan overlay = new BoundedOverlaySelector().select(scope, nodes, 16);
            HashSet<TopologyEdge> topologyEdges = new HashSet<TopologyEdge>();
            int edgeIndex = 0;
            for (DesiredDirectEdge edge : overlay.getEdges()) {
                TopologyTransport transport = edgeIndex++ % 5 == 0 ? TopologyTransport.TURN : TopologyTransport.RTC;
                topologyEdges.add(
                    new TopologyEdge(
                        EdgeId.derive(scope, edge.getFirst(), edge.getSecond()),
                        edge.getFirst(),
                        edge.getSecond(),
                        transport,
                        transport
                    )
                );
            }
            graph = new TopologyGraph(new HashSet<NodeId>(nodes), topologyEdges);
            Instant now = Instant.now();
            List<TopologySnapshot> published = new ArrayList<TopologySnapshot>();
            long revision = 1L;
            for (NodeId node : nodes) {
                published.add(
                    new TopologySnapshot(
                        scope,
                        keys.get(node).getPublicKey(),
                        "scale-" + revision,
                        revision++,
                        node,
                        keys.get(node).getPublicKey(),
                        now.minusSeconds(1),
                        now.plusSeconds(120),
                        List.of()
                    )
                );
            }
            snapshots = List.copyOf(published);
            for (NodeId node : nodes) {
                routed.put(node, new RoutedTransportEngine(node, keys.get(node), new RouteContext(node)));
            }
            for (NodeId node : nodes) {
                broadcast.put(node, new BroadcastEngine(node, new TreeContext(node)));
            }
        }

        private void close() {
            for (BroadcastEngine engine : broadcast.values()) engine.close();
            for (RoutedTransportEngine engine : routed.values()) engine.close();
        }

        private final class RouteContext implements RoutedTransportContext {

            private final NodeId local;

            private RouteContext(NodeId local) {
                this.local = local;
            }

            @Override
            public TopologyGraph currentGraph() {
                return graph;
            }

            @Override
            public Collection<TopologySnapshot> topologySnapshots(Instant now) {
                return snapshots;
            }

            @Override
            public NodeId destinationFor(NostrRTCChannel channel) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasUsableDirectTurn(NostrRTCChannel channel) {
                return false;
            }

            @Override
            public AsyncTask<Boolean> sendToDirectNeighbor(
                NodeId neighbor,
                String internalChannel,
                RouteTransportProfile profile,
                ByteBuffer payload
            ) {
                if (graph.findEdge(local, neighbor) == null) return AsyncTask.completed(Boolean.FALSE);
                AsyncTask<Boolean> sent;
                if (InternalRoutingChannels.CONTROL.equals(internalChannel)) {
                    sent = routed.get(neighbor).onDirectControl(local, payload);
                } else {
                    sent = routed.get(neighbor).onDirectData(local, payload);
                }
                return sent.then(ok -> {
                    if (!Boolean.TRUE.equals(ok)) {
                        throw new IllegalStateException(
                            "Rejected internal frame " + internalChannel + " from " + local + " to " + neighbor
                        );
                    }
                    return ok;
                });
            }

            @Override
            public boolean deliverNormalFrame(NodeId originalSource, String logicalChannel, ByteBuffer normalFrame) {
                deliveries.get(local).incrementAndGet();
                return true;
            }

            @Override
            public void routingStateChanged() {}
        }

        private final class TreeContext implements BroadcastContext {

            private final NodeId local;

            private TreeContext(NodeId local) {
                this.local = local;
            }

            @Override
            public TopologyGraph currentGraph() {
                return graph;
            }

            @Override
            public TopologyGraph graphBySnapshotId(String snapshotId) {
                return graph.getSnapshotId().equals(snapshotId) ? graph : null;
            }

            @Override
            public AsyncTask<Boolean> sendTreeEdge(NodeId child, RouteTransportProfile profile, ByteBuffer encodedFrame) {
                broadcastForwards.incrementAndGet();
                return broadcast.get(child).onTreeFrame(local, encodedFrame, Instant.now());
            }

            @Override
            public boolean deliverBroadcast(NodeId origin, String logicalChannel, ByteBuffer payload) {
                deliveries.get(local).incrementAndGet();
                return true;
            }

            @Override
            public AsyncTask<Boolean> sendAck(BroadcastAck ack) {
                broadcastAcks.incrementAndGet();
                return AsyncTask.completed(Boolean.valueOf(broadcast.get(ack.getOrigin()).onAck(ack)));
            }

            @Override
            public AsyncTask<Boolean> repairUnicast(NodeId target, ByteBuffer encodedFrame) {
                return broadcast.get(target).onRepairFrame(encodedFrame, Instant.now());
            }
        }
    }
}
