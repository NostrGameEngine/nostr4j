/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrRTCChannel;
import org.ngengine.nostr4j.rtc.delivery.DeliveryAckTimeoutException;
import org.ngengine.nostr4j.rtc.routing.packet.RoutedDataFrame;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class TestRoutedTransportEngine {

    @Test
    public void testMixedTransportFourNodeRouteSetsUpForwardsAndDelivers() {
        TestNetwork network = new TestNetwork();
        NodeId a = network.addNode(1);
        NodeId b = network.addNode(2);
        NodeId c = network.addNode(3);
        NodeId d = network.addNode(4);
        network.addEdge(a, b, TopologyTransport.TURN);
        network.addEdge(b, c, TopologyTransport.RTC);
        network.addEdge(c, d, TopologyTransport.TURN);
        network.finish();
        try {
            ByteBuffer normalFrame = normalFrame(77L, "hello routed");
            Boolean result = NGEUtils.awaitNoThrow(
                network.engines.get(a).sendRouted(d, "game", RouteTransportProfile.RELIABLE_ORDERED, normalFrame, Instant.now())
            );
            assertTrue(result.booleanValue());
            assertEquals(1, network.deliveries.get(d).get());
            assertEquals("hello routed", network.lastPayload);
            assertEquals(3, network.dataForwards.get());
            assertTrue(network.controlForwards.get() >= 6);
        } finally {
            network.close();
        }
    }

    @Test
    public void testLostAckRetriesSamePacketAndCiphertextOverDisjointRoute() throws Exception {
        TestNetwork network = new TestNetwork(75L);
        NodeId a = network.addNode(10);
        NodeId b = network.addNode(11);
        NodeId c = network.addNode(12);
        NodeId d = network.addNode(13);
        NodeId e = network.addNode(14);
        NodeId f = network.addNode(15);
        network.sourceNode = a;
        network.addEdge(a, b, TopologyTransport.RTC);
        network.addEdge(b, c, TopologyTransport.RTC);
        network.addEdge(c, d, TopologyTransport.RTC);
        network.addEdge(a, e, TopologyTransport.RTC);
        network.addEdge(e, f, TopologyTransport.RTC);
        network.addEdge(f, d, TopologyTransport.RTC);
        network.dropStatelessOrdinals.put(d, Set.of(Integer.valueOf(2)));
        network.finish();
        try {
            ByteBuffer frame = normalFrame(909L, "deliver once");
            Throwable firstFailure = null;
            try {
                network.engines
                    .get(a)
                    .sendRouted(d, "game", RouteTransportProfile.RELIABLE_ORDERED, frame, Instant.now())
                    .await();
            } catch (Throwable error) {
                firstFailure = error;
            }
            assertTrue(hasCause(firstFailure, DeliveryAckTimeoutException.class));

            Boolean retried = network.engines
                .get(a)
                .sendRouted(d, "game", RouteTransportProfile.RELIABLE_ORDERED, frame, Instant.now())
                .await();
            assertTrue(retried.booleanValue());
            assertEquals(1, network.deliveries.get(d).get());
            assertEquals(2, network.engines.get(d).getGeneratedDeliveryAckCount());
            assertEquals(1, network.engines.get(a).getCompletedDeliveryAckCount());
            assertEquals(1L, network.engines.get(a).getPayloadEncryptionCount());
            assertEquals(2, network.sourceDataHops.size());
            assertNotEquals(network.sourceDataHops.get(0), network.sourceDataHops.get(1));
            assertEquals(network.sourceCiphertexts.get(0), network.sourceCiphertexts.get(1));
        } finally {
            network.close();
        }
    }

    @Test
    public void testUnreliableDropHasNoAckOrPendingRetryState() {
        TestNetwork network = new TestNetwork(50L);
        NodeId a = network.addNode(20);
        NodeId b = network.addNode(21);
        NodeId c = network.addNode(22);
        network.sourceNode = a;
        network.dropAllSourceData = true;
        network.addEdge(a, b, TopologyTransport.RTC);
        network.addEdge(b, c, TopologyTransport.RTC);
        network.finish();
        try {
            Boolean sent = NGEUtils.awaitNoThrow(
                network.engines
                    .get(a)
                    .sendRouted(
                        c,
                        "realtime",
                        RouteTransportProfile.UNRELIABLE_UNORDERED,
                        normalFrame(1001L, "drop"),
                        Instant.now()
                    )
            );
            assertTrue(sent.booleanValue());
            assertEquals(0, network.deliveries.get(c).get());
            assertEquals(0, network.engines.get(a).getPendingAcknowledgedDeliveryCount());
            assertEquals(0, network.engines.get(c).getGeneratedDeliveryAckCount());
        } finally {
            network.close();
        }
    }

    @Test
    public void testPartialReliabilityRetransmitLimitIsEnforced() throws Exception {
        TestNetwork network = new TestNetwork(50L);
        NodeId a = network.addNode(30);
        NodeId b = network.addNode(31);
        NodeId c = network.addNode(32);
        network.sourceNode = a;
        network.addEdge(a, b, TopologyTransport.RTC);
        network.addEdge(b, c, TopologyTransport.RTC);
        network.dropStatelessOrdinals.put(c, Set.of(2, 4));
        network.finish();
        RouteTransportProfile partial = new RouteTransportProfile(false, false, Integer.valueOf(1), null);
        ByteBuffer frame = normalFrame(1101L, "partial");
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    network.engines.get(a).sendRouted(c, "partial", partial, frame, Instant.now()).await();
                } catch (Throwable expected) {
                    assertTrue(hasCause(expected, DeliveryAckTimeoutException.class));
                }
            }
            Throwable exhausted = null;
            try {
                network.engines.get(a).sendRouted(c, "partial", partial, frame, Instant.now()).await();
            } catch (Throwable error) {
                exhausted = error;
            }
            assertTrue(exhausted instanceof IllegalStateException);
            assertEquals(1L, network.engines.get(a).getPayloadEncryptionCount());
        } finally {
            network.close();
        }
    }

    private static ByteBuffer normalFrame(long packetId, String value) {
        byte[] payload = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(12 + payload.length);
        frame.putLong(packetId);
        frame.putShort((short) 0);
        frame.putShort((short) 1);
        frame.put(payload);
        frame.flip();
        return frame.asReadOnlyBuffer();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private static final class TestNetwork {

        private final RoutingScope scope = new RoutingScope(new NostrKeyPair().getPublicKey(), "engine-proto", "engine-app");
        private final Map<NodeId, NostrKeyPair> keys = new HashMap<NodeId, NostrKeyPair>();
        private final Map<NodeId, RoutedTransportEngine> engines = new HashMap<NodeId, RoutedTransportEngine>();
        private final Map<NodeId, TestContext> contexts = new HashMap<NodeId, TestContext>();
        private final Map<NodeId, AtomicInteger> deliveries = new HashMap<NodeId, AtomicInteger>();
        private final List<TopologyEdge> edges = new ArrayList<TopologyEdge>();
        private final AtomicInteger controlForwards = new AtomicInteger();
        private final AtomicInteger dataForwards = new AtomicInteger();
        private final long ackTimeoutMs;
        private final Map<NodeId, Set<Integer>> dropStatelessOrdinals = new HashMap<NodeId, Set<Integer>>();
        private final Map<NodeId, AtomicInteger> statelessOrdinals = new HashMap<NodeId, AtomicInteger>();
        private final Map<NodeId, Set<Long>> deliveredPacketIds = new HashMap<NodeId, Set<Long>>();
        private final List<NodeId> sourceDataHops = new ArrayList<NodeId>();
        private final List<String> sourceCiphertexts = new ArrayList<String>();
        private volatile TopologyGraph graph = new TopologyGraph(new HashSet<NodeId>(), new HashSet<TopologyEdge>());
        private volatile Collection<TopologySnapshot> snapshots = List.of();
        private volatile String lastPayload;
        private volatile NodeId sourceNode;
        private volatile boolean dropAllSourceData;

        private TestNetwork() {
            this(12_000L);
        }

        private TestNetwork(long ackTimeoutMs) {
            this.ackTimeoutMs = ackTimeoutMs;
        }

        private NodeId addNode(int value) {
            byte[] bytes = new byte[NodeId.SIZE];
            bytes[NodeId.SIZE - 1] = (byte) value;
            NodeId node = NodeId.fromHex(NGEUtils.bytesToHex(bytes));
            NostrKeyPair routingKeys = new NostrKeyPair();
            keys.put(node, routingKeys);
            deliveries.put(node, new AtomicInteger());
            statelessOrdinals.put(node, new AtomicInteger());
            deliveredPacketIds.put(node, new HashSet<Long>());
            TestContext context = new TestContext(node);
            contexts.put(node, context);
            engines.put(node, new RoutedTransportEngine(node, routingKeys, context, ackTimeoutMs));
            return node;
        }

        private void addEdge(NodeId first, NodeId second, TopologyTransport transport) {
            edges.add(new TopologyEdge(EdgeId.derive(scope, first, second), first, second, transport, transport));
        }

        private void finish() {
            graph = new TopologyGraph(new HashSet<NodeId>(keys.keySet()), new HashSet<TopologyEdge>(edges));
            Instant now = Instant.now();
            List<TopologySnapshot> published = new ArrayList<TopologySnapshot>();
            long revision = 1L;
            for (Map.Entry<NodeId, NostrKeyPair> entry : keys.entrySet()) {
                published.add(
                    new TopologySnapshot(
                        scope,
                        entry.getValue().getPublicKey(),
                        "session-" + revision,
                        revision++,
                        entry.getKey(),
                        entry.getValue().getPublicKey(),
                        now.minusSeconds(1),
                        now.plusSeconds(60),
                        List.of()
                    )
                );
            }
            snapshots = List.copyOf(published);
        }

        private void close() {
            for (RoutedTransportEngine engine : engines.values()) engine.close();
        }

        private final class TestContext implements RoutedTransportContext {

            private final NodeId local;

            private TestContext(NodeId local) {
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
                RoutedTransportEngine target = engines.get(neighbor);
                if (target == null || graph.findEdge(local, neighbor) == null) {
                    return AsyncTask.completed(Boolean.FALSE);
                }
                if (InternalRoutingChannels.CONTROL.equals(internalChannel)) {
                    controlForwards.incrementAndGet();
                    ByteBuffer type = payload.asReadOnlyBuffer().order(java.nio.ByteOrder.BIG_ENDIAN);
                    if (type.remaining() >= 4 && type.getInt() == 0x44433443) {
                        int ordinal = statelessOrdinals.get(local).incrementAndGet();
                        Set<Integer> dropped = dropStatelessOrdinals.get(local);
                        if (dropped != null && dropped.contains(Integer.valueOf(ordinal))) {
                            return AsyncTask.completed(Boolean.TRUE);
                        }
                    }
                    return target.onDirectControl(local, payload.asReadOnlyBuffer());
                }
                dataForwards.incrementAndGet();
                if (local.equals(sourceNode)) {
                    RoutedDataFrame routed = RoutedDataFrame.decode(payload, Instant.now());
                    sourceDataHops.add(neighbor);
                    sourceCiphertexts.add(NGEUtils.bytesToHex(bytes(routed.getCiphertext())));
                    if (dropAllSourceData) return AsyncTask.completed(Boolean.TRUE);
                }
                return target.onDirectData(local, payload.asReadOnlyBuffer());
            }

            @Override
            public boolean deliverNormalFrame(NodeId originalSource, String logicalChannel, ByteBuffer normalFrame) {
                ByteBuffer data = normalFrame.asReadOnlyBuffer();
                long packetId = data.getLong(data.position());
                if (!deliveredPacketIds.get(local).add(Long.valueOf(packetId))) {
                    return true;
                }
                data.position(data.position() + 12);
                byte[] payload = new byte[data.remaining()];
                data.get(payload);
                lastPayload = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
                deliveries.get(local).incrementAndGet();
                return true;
            }

            @Override
            public void routingStateChanged() {}
        }

        private static byte[] bytes(ByteBuffer input) {
            ByteBuffer data = input.asReadOnlyBuffer();
            byte[] result = new byte[data.remaining()];
            data.get(result);
            return result;
        }
    }
}
