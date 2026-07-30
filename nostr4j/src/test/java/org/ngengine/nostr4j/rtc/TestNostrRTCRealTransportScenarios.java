/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.delivery.DeliveryRouteUnavailableException;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutePath;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutedTransportContext;
import org.ngengine.nostr4j.rtc.routing.RoutedTransportEngine;
import org.ngengine.nostr4j.rtc.routing.RoutedTransportTestAccess;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.routing.WeightedRoutePlanner;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.turn.ref.TurnServer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

/**
 * Exercises the native JVM libdatachannel transport. Fault-injection tests elsewhere use a deterministic fake
 * transport; these tests intentionally verify actual PeerConnections and DataChannels.
 */
public class TestNostrRTCRealTransportScenarios {

    private static final String APPLICATION_ID = "real-rtc-test";
    private static final String PROTOCOL_ID = "real-rtc-test-v1";

    @Test
    public void directPayloadCrossesANativeRtcDataChannel() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> payload = new AtomicReference<String>();
        NativeRtcWire wire = new NativeRtcWire(
            "alice-direct",
            "bob-direct",
            (atLeft, message) -> {
                byte[] data = new byte[message.remaining()];
                message.get(data);
                payload.set(new String(data, StandardCharsets.UTF_8));
                received.countDown();
            }
        );
        try {
            wire.connect();
            assertTrue(wire.write(true, bytes("native-direct")).await().booleanValue());
            assertTrue("Native RTC payload was not delivered", received.await(5, TimeUnit.SECONDS));
            assertEquals("native-direct", payload.get());
        } finally {
            wire.close();
        }
    }

    @Test
    public void onionPayloadCrossesTwoThreeAndFourNativeRtcHops() throws Exception {
        RealRoutingNetwork network = new RealRoutingNetwork(false);
        try {
            RoutingNode a = network.addNode("chain-a");
            RoutingNode b = network.addNode("chain-b");
            RoutingNode c = network.addNode("chain-c");
            RoutingNode d = network.addNode("chain-d");
            RoutingNode e = network.addNode("chain-e");
            network.addLink(a, b, TopologyTransport.RTC);
            network.addLink(b, c, TopologyTransport.RTC);
            network.addLink(c, d, TopologyTransport.RTC);
            network.addLink(d, e, TopologyTransport.RTC);
            network.start();

            network.assertRoutedDelivery(a, c, 201L, "two-native-rtc-hops");
            network.assertRoutedDelivery(a, d, 202L, "three-native-rtc-hops");
            network.assertRoutedDelivery(a, e, 203L, "four-native-rtc-hops");

            assertEquals(1, network.deliveries(c));
            assertEquals(1, network.deliveries(d));
            assertEquals(1, network.deliveries(e));
            assertTrue("Expected native RTC forwarding", network.rtcFrames.get() > 0);
            assertEquals("Unexpected TURN forwarding in RTC-only chain", 0, network.turnFrames.get());
        } finally {
            network.close();
        }
    }

    @Test
    public void nonResponsiveRouteFailsThenOfflineTopologyReroutesAndRecovers() throws Exception {
        RealRoutingNetwork network = new RealRoutingNetwork(false);
        try {
            RoutingNode a = network.addNode("reroute-a");
            RoutingNode b = network.addNode("reroute-b");
            RoutingNode c = network.addNode("reroute-c");
            RoutingNode d = network.addNode("reroute-d");
            network.addLink(a, b, TopologyTransport.RTC);
            network.addLink(b, d, TopologyTransport.RTC);
            network.addLink(a, c, TopologyTransport.RTC);
            network.addLink(c, d, TopologyTransport.RTC);
            network.start();

            RoutePath preferred = new WeightedRoutePlanner().plan(network.graph, a.id, d.id, Instant.now()).get(0);
            RealRoutingLink failedFirstHop = network.link(preferred.getNodes().get(0), preferred.getNodes().get(1));
            failedFirstHop.responsive.set(false);

            Throwable nonResponsiveFailure = network.routedFailure(a, d, 301L, "unresponsive");
            assertTrue(
                "Expected a route-unavailable failure for a non-responsive physical peer",
                hasCause(nonResponsiveFailure, DeliveryRouteUnavailableException.class)
            );

            network.setLinkInTopology(failedFirstHop, false);
            network.assertRoutedDelivery(a, d, 302L, "alternate-after-offline");
            assertEquals(1, network.deliveries(d));

            failedFirstHop.responsive.set(true);
            network.setLinkInTopology(failedFirstHop, true);
            network.assertRoutedDelivery(a, d, 303L, "after-route-recovery");
            assertEquals(2, network.deliveries(d));
        } finally {
            network.close();
        }
    }

    @Test
    public void onionRouteMixesNativeRtcAndRealLocalTurnLinks() throws Exception {
        RealRoutingNetwork network = new RealRoutingNetwork(true);
        try {
            RoutingNode a = network.addNode("mixed-a");
            RoutingNode b = network.addNode("mixed-b");
            RoutingNode c = network.addNode("mixed-c");
            RoutingNode d = network.addNode("mixed-d");
            network.addLink(a, b, TopologyTransport.RTC);
            network.addLink(b, c, TopologyTransport.TURN);
            network.addLink(c, d, TopologyTransport.RTC);
            network.start();

            network.assertRoutedDelivery(a, d, 401L, "native-rtc-turn-native-rtc");

            assertEquals(1, network.deliveries(d));
            assertTrue("Expected native RTC frames on the mixed route", network.rtcFrames.get() > 0);
            assertTrue("Expected real local TURN frames on the mixed route", network.turnFrames.get() > 0);
        } finally {
            network.close();
        }
    }

    private static ByteBuffer bytes(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteBuffer normalFrame(long packetId, String value) {
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
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

    private static int findFreePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMs, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25L);
        }
        throw new AssertionError(message);
    }

    private static NostrRTCPeer remotePeer(PeerEndpoint endpoint, NostrKeyPair roomKeys) {
        return new NostrRTCPeer(
            endpoint.local.getPubkey(),
            APPLICATION_ID,
            PROTOCOL_ID,
            endpoint.local.getSessionId(),
            roomKeys.getPublicKey(),
            endpoint.local.getTurnServer()
        );
    }

    private static final class PeerEndpoint {

        private final NostrKeyPair roomKeys;
        private final NostrRTCLocalPeer local;
        private final AsyncExecutor executor;
        private final NostrTURNPool turnPool = new NostrTURNPool();

        private PeerEndpoint(String sessionId, NostrKeyPair roomKeys, String turnUrl) {
            this.roomKeys = roomKeys;
            this.local =
                new NostrRTCLocalPeer(
                    NostrKeyPairSigner.generate(),
                    Collections.emptyList(),
                    APPLICATION_ID,
                    PROTOCOL_ID,
                    sessionId,
                    roomKeys,
                    turnUrl
                );
            this.executor = NGEPlatform.get().newAsyncExecutor("real-rtc-" + sessionId);
        }

        private void close() {
            turnPool.close();
            executor.close();
        }
    }

    private static final class RealLink {

        private final NostrRTCSocket leftSocket;
        private final NostrRTCSocket rightSocket;

        private RealLink(PeerEndpoint left, PeerEndpoint right, RTCSettings settings) {
            NostrKeyPair roomKeys = left.roomKeys;
            this.leftSocket =
                new NostrRTCSocket(
                    left.executor,
                    remotePeer(right, roomKeys),
                    roomKeys,
                    left.local,
                    settings,
                    left.local.getTurnServer(),
                    left.turnPool
                );
            this.rightSocket =
                new NostrRTCSocket(
                    right.executor,
                    remotePeer(left, roomKeys),
                    roomKeys,
                    right.local,
                    settings,
                    right.local.getTurnServer(),
                    right.turnPool
                );
        }

        private ChannelPair prepareChannel(String name) {
            NostrRTCChannel leftChannel = leftSocket.createChannel(name);
            NostrRTCChannel rightChannel = rightSocket.createChannel(name);
            return new ChannelPair(leftChannel, rightChannel);
        }

        private void close() {
            leftSocket.close();
            rightSocket.close();
        }
    }

    private static final class RealRoutingNetwork {

        private final NostrKeyPair roomKeys = new NostrKeyPair();
        private final RoutingScope scope = new RoutingScope(roomKeys.getPublicKey(), PROTOCOL_ID, APPLICATION_ID);
        private final List<RoutingNode> nodes = new ArrayList<RoutingNode>();
        private final List<RealRoutingLink> links = new ArrayList<RealRoutingLink>();
        private final Map<String, RealRoutingLink> linksByNodes = new HashMap<String, RealRoutingLink>();
        private final Map<NodeId, RoutedTransportEngine> engines = new HashMap<NodeId, RoutedTransportEngine>();
        private final Map<NodeId, AtomicInteger> deliveryCounts = new HashMap<NodeId, AtomicInteger>();
        private final Map<NodeId, String> lastPayloads = new ConcurrentHashMap<NodeId, String>();
        private final AtomicInteger rtcFrames = new AtomicInteger();
        private final AtomicInteger turnFrames = new AtomicInteger();
        private final AtomicInteger transportMismatches = new AtomicInteger();
        private final TurnServer turnServer;
        private final String turnUrl;
        private volatile TopologyGraph graph = new TopologyGraph(Collections.emptySet(), Collections.emptySet());
        private volatile Collection<TopologySnapshot> snapshots = Collections.emptyList();

        private RealRoutingNetwork(boolean withTurn) throws Exception {
            if (withTurn) {
                this.turnServer = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
                this.turnServer.start();
                this.turnUrl = "ws://127.0.0.1:" + turnServer.getPort() + "/turn";
            } else {
                this.turnServer = null;
                this.turnUrl = null;
            }
        }

        private RoutingNode addNode(String sessionId) {
            RoutingNode node = new RoutingNode(new PeerEndpoint(sessionId, roomKeys, turnUrl));
            nodes.add(node);
            deliveryCounts.put(node.id, new AtomicInteger());
            return node;
        }

        private RealRoutingLink addLink(RoutingNode first, RoutingNode second, TopologyTransport transport) {
            if (transport == TopologyTransport.TURN && turnUrl == null) {
                throw new IllegalStateException("TURN link requested without a local TURN server");
            }
            RealRoutingLink link = new RealRoutingLink(this, first, second, transport);
            links.add(link);
            linksByNodes.put(linkKey(first.id, second.id), link);
            return link;
        }

        private void start() throws Exception {
            rebuildGraph();
            Instant now = Instant.now();
            List<TopologySnapshot> currentSnapshots = new ArrayList<TopologySnapshot>();
            long revision = 1L;
            for (RoutingNode node : nodes) {
                currentSnapshots.add(
                    new TopologySnapshot(
                        scope,
                        node.routingKeys.getPublicKey(),
                        node.endpoint.local.getSessionId(),
                        revision++,
                        node.id,
                        node.routingKeys.getPublicKey(),
                        now.minusSeconds(1),
                        now.plusSeconds(120),
                        Collections.emptyList()
                    )
                );
            }
            snapshots = List.copyOf(currentSnapshots);
            for (RoutingNode node : nodes) {
                engines.put(node.id, new RoutedTransportEngine(node.id, node.routingKeys, new RouteContext(node.id)));
            }
            for (RealRoutingLink link : links) link.start();
        }

        private void assertRoutedDelivery(RoutingNode source, RoutingNode destination, long packetId, String expectedPayload)
            throws Exception {
            Boolean delivered = RoutedTransportTestAccess
                .send(
                    engines.get(source.id),
                    destination.id,
                    "application",
                    RouteTransportProfile.RELIABLE_ORDERED,
                    normalFrame(packetId, expectedPayload)
                )
                .await();
            assertTrue("Routed send returned false for " + expectedPayload, delivered.booleanValue());
            assertEquals(expectedPayload, lastPayloads.get(destination.id));
            assertEquals("Transport callback disagreed with the declared topology", 0, transportMismatches.get());
        }

        private Throwable routedFailure(RoutingNode source, RoutingNode destination, long packetId, String payload) {
            try {
                Boolean delivered = RoutedTransportTestAccess
                    .send(
                        engines.get(source.id),
                        destination.id,
                        "application",
                        RouteTransportProfile.RELIABLE_ORDERED,
                        normalFrame(packetId, payload)
                    )
                    .await();
                return new AssertionError("Expected routed send to fail, result=" + delivered);
            } catch (Throwable error) {
                return error;
            }
        }

        private int deliveries(RoutingNode node) {
            return deliveryCounts.get(node.id).get();
        }

        private RealRoutingLink link(NodeId first, NodeId second) {
            RealRoutingLink link = linksByNodes.get(linkKey(first, second));
            if (link == null) throw new IllegalArgumentException("No physical link between " + first + " and " + second);
            return link;
        }

        private void setLinkInTopology(RealRoutingLink link, boolean available) {
            link.inTopology.set(available);
            rebuildGraph();
        }

        private void rebuildGraph() {
            Set<NodeId> graphNodes = new HashSet<NodeId>();
            for (RoutingNode node : nodes) graphNodes.add(node.id);
            Set<TopologyEdge> graphEdges = new HashSet<TopologyEdge>();
            for (RealRoutingLink link : links) {
                if (link.inTopology.get()) graphEdges.add(link.edge);
            }
            graph = new TopologyGraph(graphNodes, graphEdges);
        }

        private void onFrame(
            RoutingNode receiver,
            RoutingNode sender,
            String channel,
            ByteBuffer payload,
            boolean viaTurn,
            TopologyTransport expectedTransport
        ) {
            if (viaTurn != (expectedTransport == TopologyTransport.TURN)) transportMismatches.incrementAndGet();
            RoutedTransportEngine target = engines.get(receiver.id);
            if (target == null) return;
            if (InternalRoutingChannels.CONTROL.equals(channel)) {
                target.onDirectControl(sender.id, payload.asReadOnlyBuffer());
            } else {
                target.onDirectData(sender.id, payload.asReadOnlyBuffer());
            }
        }

        private void close() {
            for (RealRoutingLink link : links) link.close();
            for (RoutedTransportEngine engine : engines.values()) engine.close();
            for (RoutingNode node : nodes) {
                node.endpoint.close();
                node.routingKeys.destroy();
            }
            if (turnServer != null) {
                try {
                    turnServer.stop();
                } catch (Exception error) {
                    throw new IllegalStateException("Failed to stop local TURN server", error);
                }
            }
            roomKeys.destroy();
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
                RealRoutingLink link = linksByNodes.get(linkKey(local, neighbor));
                if (link == null || !link.inTopology.get() || graph.findEdge(local, neighbor) == null) {
                    return AsyncTask.completed(Boolean.FALSE);
                }
                return link.send(local, internalChannel, payload);
            }

            @Override
            public boolean deliverNormalFrame(NodeId originalSource, String logicalChannel, ByteBuffer normalFrame) {
                ByteBuffer data = normalFrame.asReadOnlyBuffer();
                data.position(data.position() + 12);
                byte[] payload = new byte[data.remaining()];
                data.get(payload);
                lastPayloads.put(local, new String(payload, StandardCharsets.UTF_8));
                deliveryCounts.get(local).incrementAndGet();
                return true;
            }

            @Override
            public void routingStateChanged() {}
        }
    }

    private static final class RoutingNode {

        private final PeerEndpoint endpoint;
        private final NostrKeyPair routingKeys = new NostrKeyPair();
        private final NodeId id;

        private RoutingNode(PeerEndpoint endpoint) {
            this.endpoint = endpoint;
            this.id =
                NodeId.derive(
                    new RoutingScope(endpoint.roomKeys.getPublicKey(), PROTOCOL_ID, APPLICATION_ID),
                    endpoint.local.getPubkey(),
                    endpoint.local.getSessionId()
                );
        }
    }

    private static final class RealRoutingLink {

        private final RealRoutingNetwork network;
        private final RoutingNode left;
        private final RoutingNode right;
        private final TopologyTransport transport;
        private final TopologyEdge edge;
        private final PeerEndpoint physicalLeft;
        private final PeerEndpoint physicalRight;
        private final RealLink physical;
        private final ChannelPair wireChannel;
        private final NativeRtcWire nativeRtc;
        private final AtomicBoolean responsive = new AtomicBoolean(true);
        private final AtomicBoolean inTopology = new AtomicBoolean(true);

        private RealRoutingLink(RealRoutingNetwork network, RoutingNode left, RoutingNode right, TopologyTransport transport) {
            this.network = network;
            this.left = left;
            this.right = right;
            this.transport = transport;
            this.edge =
                new TopologyEdge(EdgeId.derive(network.scope, left.id, right.id), left.id, right.id, transport, transport);
            if (transport == TopologyTransport.RTC) {
                this.physicalLeft = null;
                this.physicalRight = null;
                this.physical = null;
                this.wireChannel = null;
                this.nativeRtc =
                    new NativeRtcWire(
                        left.endpoint.local.getSessionId() + "-to-" + right.endpoint.local.getSessionId(),
                        right.endpoint.local.getSessionId() + "-to-" + left.endpoint.local.getSessionId(),
                        (atLeft, payload) -> dispatchWire(atLeft.booleanValue(), payload, false)
                    );
            } else {
                this.nativeRtc = null;
                this.physicalLeft =
                    new PeerEndpoint(
                        left.endpoint.local.getSessionId() + "-to-" + right.endpoint.local.getSessionId(),
                        network.roomKeys,
                        network.turnUrl
                    );
                this.physicalRight =
                    new PeerEndpoint(
                        right.endpoint.local.getSessionId() + "-to-" + left.endpoint.local.getSessionId(),
                        network.roomKeys,
                        network.turnUrl
                    );
                this.physical = new RealLink(physicalLeft, physicalRight, RTCSettings.DEFAULT);
                this.wireChannel = physical.prepareChannel("routewire");
            }
        }

        private void start() throws Exception {
            if (transport == TopologyTransport.RTC) {
                nativeRtc.connect();
            } else {
                physical.leftSocket.setForceTURN(true);
                physical.rightSocket.setForceTURN(true);
                warmTurn(wireChannel, "routewire");
                attachReceiver(wireChannel);
            }
        }

        private void warmTurn(ChannelPair pair, String name) throws Exception {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline) {
                pair.left.write(bytes("warm-left")).await();
                pair.right.write(bytes("warm-right")).await();
                if (pair.left.isTurnReady() && pair.right.isTurnReady()) return;
                Thread.sleep(50L);
            }
            throw new AssertionError("Real TURN channel did not become ready: " + name);
        }

        private void attachReceiver(ChannelPair pair) {
            pair.left.addListener(new RoutedFrameReceiver(this, true));
            pair.right.addListener(new RoutedFrameReceiver(this, false));
        }

        private AsyncTask<Boolean> send(NodeId source, String channel, ByteBuffer payload) {
            if (!responsive.get()) return AsyncTask.completed(Boolean.FALSE);
            byte frameType = InternalRoutingChannels.CONTROL.equals(channel) ? (byte) 1 : (byte) 2;
            ByteBuffer framed = ByteBuffer.allocate(1 + payload.remaining());
            framed.put(frameType);
            framed.put(payload.asReadOnlyBuffer());
            framed.flip();
            boolean fromLeft;
            if (source.equals(left.id)) {
                fromLeft = true;
            } else if (source.equals(right.id)) {
                fromLeft = false;
            } else {
                return AsyncTask.completed(Boolean.FALSE);
            }
            AsyncTask<Boolean> sendTask = transport == TopologyTransport.RTC
                ? nativeRtc.write(fromLeft, framed.asReadOnlyBuffer())
                : (fromLeft ? wireChannel.left : wireChannel.right).write(framed.asReadOnlyBuffer());
            return sendTask.then(sent -> {
                if (Boolean.TRUE.equals(sent)) {
                    if (transport == TopologyTransport.TURN) {
                        network.turnFrames.incrementAndGet();
                    } else {
                        network.rtcFrames.incrementAndGet();
                    }
                }
                return sent;
            });
        }

        private void dispatchWire(boolean atLeft, ByteBuffer payload, boolean viaTurn) {
            if (!payload.hasRemaining()) return;
            byte frameType = payload.get();
            String logicalChannel = frameType == 1
                ? InternalRoutingChannels.CONTROL
                : InternalRoutingChannels.data(RouteTransportProfile.RELIABLE_ORDERED);
            network.onFrame(atLeft ? left : right, atLeft ? right : left, logicalChannel, payload.slice(), viaTurn, transport);
        }

        private void close() {
            if (nativeRtc != null) nativeRtc.close();
            if (physical != null) physical.close();
            if (physicalLeft != null) physicalLeft.close();
            if (physicalRight != null) physicalRight.close();
        }
    }

    private static final class NativeRtcWire {

        private final RTCTransport left;
        private final RTCTransport right;
        private final WireTransportListener leftListener;
        private final WireTransportListener rightListener;
        private volatile RTCDataChannel leftChannel;
        private volatile RTCDataChannel rightChannel;

        private NativeRtcWire(String leftId, String rightId, BiConsumer<Boolean, ByteBuffer> receiver) {
            this.left = NGEPlatform.get().newRTCTransport(Duration.ofSeconds(8), leftId, Collections.emptyList());
            this.right = NGEPlatform.get().newRTCTransport(Duration.ofSeconds(8), rightId, Collections.emptyList());
            this.leftListener = new WireTransportListener(true, left, right, receiver, channel -> leftChannel = channel);
            this.rightListener = new WireTransportListener(false, right, left, receiver, channel -> rightChannel = channel);
            left.addListener(leftListener);
            right.addListener(rightListener);
        }

        private void connect() throws Exception {
            String offer = left.listen().await();
            String answer = right.connect(offer).await();
            left.connect(answer).await();
            left.addRemoteIceCandidates(rightListener.candidates());
            right.addRemoteIceCandidates(leftListener.candidates());
            awaitCondition(
                () -> left.isConnected() && right.isConnected(),
                8_000L,
                "Native routing PeerConnection did not connect"
            );
            leftChannel = left.createDataChannel("routewire", PROTOCOL_ID, true, true, 0, null).await();
            awaitCondition(
                () -> leftChannel != null && rightChannel != null,
                8_000L,
                "Native routing DataChannel did not connect"
            );
        }

        private AsyncTask<Boolean> write(boolean fromLeft, ByteBuffer payload) {
            RTCDataChannel channel = fromLeft ? leftChannel : rightChannel;
            if (channel == null) return AsyncTask.completed(Boolean.FALSE);
            return AsyncTask.create((resolve, reject) ->
                channel
                    .write(payload)
                    .then(ignored -> {
                        resolve.accept(Boolean.TRUE);
                        return null;
                    })
                    .catchException(error -> resolve.accept(Boolean.FALSE))
            );
        }

        private void close() {
            left.close();
            right.close();
        }
    }

    private static final class WireTransportListener implements RTCTransportListener {

        private final boolean atLeft;
        private final RTCTransport local;
        private final RTCTransport remote;
        private final BiConsumer<Boolean, ByteBuffer> receiver;
        private final java.util.function.Consumer<RTCDataChannel> readyChannel;
        private final List<RTCTransportIceCandidate> localCandidates = new ArrayList<RTCTransportIceCandidate>();

        private WireTransportListener(
            boolean atLeft,
            RTCTransport local,
            RTCTransport remote,
            BiConsumer<Boolean, ByteBuffer> receiver,
            java.util.function.Consumer<RTCDataChannel> readyChannel
        ) {
            this.atLeft = atLeft;
            this.local = local;
            this.remote = remote;
            this.receiver = receiver;
            this.readyChannel = readyChannel;
        }

        private synchronized Collection<RTCTransportIceCandidate> candidates() {
            return List.copyOf(localCandidates);
        }

        @Override
        public synchronized void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
            localCandidates.add(candidate);
            remote.addRemoteIceCandidates(List.of(candidate));
        }

        @Override
        public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer message) {
            receiver.accept(Boolean.valueOf(atLeft), message.asReadOnlyBuffer());
        }

        @Override
        public void onRTCChannelError(RTCDataChannel channel, Throwable error) {}

        @Override
        public void onRTCChannelReady(RTCDataChannel channel) {
            if ("routewire".equals(channel.getName())) readyChannel.accept(channel);
        }

        @Override
        public void onRTCBufferedAmountLow(RTCDataChannel channel) {}

        @Override
        public void onRTCChannelClosed(RTCDataChannel channel) {}

        @Override
        public void onRTCDisconnected(String reason) {}

        @Override
        public void onRTCConnected() {}
    }

    private static final class RoutedFrameReceiver implements NostrRTCChannelListener {

        private final RealRoutingLink link;
        private final boolean atLeft;

        private RoutedFrameReceiver(RealRoutingLink link, boolean atLeft) {
            this.link = link;
            this.atLeft = atLeft;
        }

        @Override
        public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer payload, boolean isTurn) {
            link.dispatchWire(atLeft, payload, isTurn);
        }

        @Override
        public void onRTCChannelError(NostrRTCChannel channel, Throwable error) {}

        @Override
        public void onRTCChannelClosed(NostrRTCChannel channel) {}

        @Override
        public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
    }

    private static String linkKey(NodeId first, NodeId second) {
        return first.compareTo(second) <= 0 ? first.asHex() + ":" + second.asHex() : second.asHex() + ":" + first.asHex();
    }

    private static final class ChannelPair {

        private final NostrRTCChannel left;
        private final NostrRTCChannel right;

        private ChannelPair(NostrRTCChannel left, NostrRTCChannel right) {
            this.left = left;
            this.right = right;
        }
    }
}
