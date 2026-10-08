/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.rtc.routing.topology.DirectNeighborManager;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyControlPlane;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.turn.ref.TurnServer;
import org.ngengine.platform.AsyncTask;

/**
 * Actual native RTC and encrypted signaling; the relay delivery is an in-process bus.
 */
public class TestNostrRTCResilienceIntegration {

    private static final String APP = "resilience-native", PROTO = "resilience-v1";

    private static RTCSettings settings(int k) {
        return RTCSettings
            .getDefault(APP, PROTO)
            .withSignalingRelays(List.of())
            .withStunServers(List.of())
            .withMaxDirectPeers(k)
            .withRoomLoopInterval(Duration.ofMillis(100))
            .withConnectionMinimumLifetime(Duration.ofMillis(200));
    }

    private static final class Bus extends NostrPool {

        volatile boolean deliverTopology;
        final AtomicBoolean dropFirstOffer = new AtomicBoolean();
        final AtomicBoolean dropFirstAnswer = new AtomicBoolean();
        volatile boolean holdOffers;
        final List<SignedNostrEvent> heldOffers = new CopyOnWriteArrayList<>();
        final Map<String, NostrRTCLocalPeer> locals = new ConcurrentHashMap<>();
        final List<NostrRTCSignaling> subscribers = new CopyOnWriteArrayList<>();
        final List<NostrRTCRoom> rooms = new CopyOnWriteArrayList<>();
        final List<TopologyControlPlane> topologyPlanes = new CopyOnWriteArrayList<>();
        final AtomicInteger maxResources = new AtomicInteger();
        final Method receive;
        final Method receiveTopology;

        Bus() throws Exception {
            receive = NostrRTCSignaling.class.getDeclaredMethod("onSubEvent", SignedNostrEvent.class, boolean.class);
            receive.setAccessible(true);
            receiveTopology =
                TopologyControlPlane.class.getDeclaredMethod("acceptEvent", SignedNostrEvent.class, Instant.class);
            receiveTopology.setAccessible(true);
        }

        @Override
        public AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent event) {
            if (event.getKind() == 25050) {
                assertTrue(
                    java.util.Set
                        .of("connect", "disconnect", "offer", "answer", "route")
                        .contains(event.getFirstTagFirstValue("t"))
                );
                for (String tag : List.of("link-admission", "link-attempt", "target-session")) assertNull(
                    event.getFirstTagFirstValue(tag)
                );
            }
            if ("offer".equals(event.getFirstTagFirstValue("t")) && dropFirstOffer.compareAndSet(true, false)) {
                return AsyncTask.completed(Collections.emptyList());
            }
            if (
                "answer".equals(event.getFirstTagFirstValue("t")) && dropFirstAnswer.compareAndSet(true, false)
            ) return AsyncTask.completed(Collections.emptyList());
            if (holdOffers && "offer".equals(event.getFirstTagFirstValue("t"))) {
                heldOffers.add(event);
                return AsyncTask.completed(Collections.emptyList());
            }
            if (event.getKind() == 25050) for (NostrRTCSignaling subscriber : subscribers) {
                try {
                    receive.invoke(subscriber, event, false);
                } catch (Exception error) {
                    return AsyncTask.failed(error);
                }
            }
            if (deliverTopology && event.getKind() == 30350) for (TopologyControlPlane plane : topologyPlanes) {
                try {
                    receiveTopology.invoke(plane, event, Instant.now());
                } catch (Exception error) {
                    return AsyncTask.failed(error);
                }
            }
            for (NostrRTCRoom room : rooms) if (room.getConnectionDiagnostics().getMaxDirectPeers() == 4) {
                maxResources.accumulateAndGet(room.getConnectionDiagnostics().getOccupiedResources(), Math::max);
            }
            return AsyncTask.completed(Collections.emptyList());
        }

        NostrRTCSignaling add(NostrRTCRoom room) throws Exception {
            Field f = NostrRTCRoom.class.getDeclaredField("signaling");
            f.setAccessible(true);
            NostrRTCSignaling signal = (NostrRTCSignaling) f.get(room);
            NostrRTCLocalPeer local = (NostrRTCLocalPeer) room.getLocalPeerInfo();
            locals.put(local.getPubkey().asHex(), local);
            subscribers.add(signal);
            rooms.add(room);
            Field topology = NostrRTCRoom.class.getDeclaredField("topologyControl");
            topology.setAccessible(true);
            topologyPlanes.add((TopologyControlPlane) topology.get(room));
            return signal;
        }
    }

    @Test
    public void asymmetricSelectionAndPubkeyPrecedenceConnectOverNativeRtc() throws Exception {
        try (NostrKeyPair roomKeys = new NostrKeyPair()) {
            Bus bus = new Bus();
            bus.dropFirstOffer.set(true);
            bus.dropFirstAnswer.set(true);
            NostrRTCLocalPeer left = local(roomKeys, "left"), right = local(roomKeys, "right");
            // Only the larger public key sends offers; the other endpoint reserves capacity independently.
            NostrRTCLocalPeer initiator = left.getPubkey().asHex().compareTo(right.getPubkey().asHex()) > 0 ? left : right;
            NostrRTCLocalPeer acceptor = initiator == left ? right : left;
            NostrRTCRoom a = new NostrRTCRoom(settings(2), initiator, roomKeys, bus, null);
            NostrRTCRoom b = new NostrRTCRoom(settings(64), acceptor, roomKeys, bus, null);
            NostrRTCSignaling sa = bus.add(a), sb = bus.add(b);
            try {
                // Suppress b's outbound scans while keeping incoming signaling active.
                sa.start(true).await();
                sb.start(true).await();
                a.start().await();
                sa.sendAnnounce("").await();
                sb.sendAnnounce("").await();
                try {
                    await(
                        () ->
                            a.getConnectionDiagnostics().getEstablishedLinks() == 1 &&
                            b.getConnectionDiagnostics().getEstablishedLinks() == 1,
                        15_000
                    );
                } catch (AssertionError error) {
                    describe(a);
                    describe(b);
                    throw error;
                }
                AtomicInteger delivered = new AtomicInteger();
                b.addMessageListener((peer, socket, channel, data, turn) -> {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    if ("after-offer-answer".equals(new String(bytes, StandardCharsets.UTF_8))) delivered.incrementAndGet();
                });
                NostrRTCPeer target = a.getPeers().iterator().next();
                a.send(target, ByteBuffer.wrap("after-offer-answer".getBytes(StandardCharsets.UTF_8))).await();
                await(() -> delivered.get() == 1, 5000);
                assertEquals(1, delivered.get());
                assertEquals("RTC", a.getConnectionDiagnostics().getCandidates().get(0).getPhysicalTransport());
            } finally {
                a.close();
                b.close();
                bus.close();
            }
        }
    }

    @Test
    public void nativeSwapPreservesLogicalEndpointsAndHonorsIndependent16And4Caps() throws Exception {
        try (NostrKeyPair roomKeys = new NostrKeyPair()) {
            Bus bus = new Bus();
            NostrRTCLocalPeer mainPeer = local(roomKeys, "main");
            NostrRTCRoom main = new NostrRTCRoom(settings(4), mainPeer, roomKeys, bus, null);
            // Isolate the native capacity/swap contract from the separate partition-repair scenario.
            java.lang.reflect.Constructor<DirectNeighborManager> constructor =
                DirectNeighborManager.class.getDeclaredConstructor(Duration.class, Duration.class);
            constructor.setAccessible(true);
            Field neighbors = NostrRTCRoom.class.getDeclaredField("neighborManager");
            neighbors.setAccessible(true);
            neighbors.set(main, constructor.newInstance(Duration.ofHours(1), Duration.ofSeconds(30)));
            List<NostrRTCRoom> remotes = new ArrayList<>();
            List<NostrRTCSignaling> signaling = new ArrayList<>();
            Map<String, Float> priorities = new ConcurrentHashMap<>();
            main.setDiscoveryPriority(peer -> priorities.getOrDefault(peer.getSessionId(), 0f));
            AtomicInteger departures = new AtomicInteger();
            main.addDisconnectionListener((p, s) -> departures.incrementAndGet());
            NostrRTCSignaling mainSignal = bus.add(main);
            try {
                for (int i = 0; i < 6; i++) {
                    NostrRTCLocalPeer remotePeer = local(roomKeys, "remote-" + i);
                    NostrRTCRoom remote = new NostrRTCRoom(
                        settings(16).withConnectionRetryInitialDelay(Duration.ofSeconds(2)),
                        remotePeer,
                        roomKeys,
                        bus,
                        null
                    );
                    remote.setDiscoveryPriority(peer -> peer.getPubkey().equals(mainPeer.getPubkey()) ? 1f : -1f);
                    remotes.add(remote);
                    signaling.add(bus.add(remote));
                }
                mainSignal.start(true).await();
                for (NostrRTCSignaling signal : signaling) signal.start(true).await();
                mainSignal.sendAnnounce("").await();
                for (NostrRTCSignaling signal : signaling) signal.sendAnnounce("").await();
                await(() -> main.getPeers().size() == 6 && main.getConnectionDiagnostics().getCandidates().size() == 6, 5000);
                NostrRTCPeer probeTarget = main
                    .getConnectionDiagnostics()
                    .getCandidates()
                    .stream()
                    .filter(c -> c.getRole().equals("OPTIONAL"))
                    .findFirst()
                    .orElseThrow()
                    .getPeer();
                priorities.put(probeTarget.getSessionId(), -1f);
                main.setDiscoveryPriority(peer -> priorities.getOrDefault(peer.getSessionId(), 0f));
                await(
                    () ->
                        main
                            .getConnectionDiagnostics()
                            .getCandidates()
                            .stream()
                            .anyMatch(c -> c.getPeer().equals(probeTarget) && c.getState().equals("EXCLUDED")),
                    5000
                );
                main.start().await();
                for (NostrRTCRoom remote : remotes) remote.start().await();
                try {
                    await(
                        () -> main.getPeers().size() == 6 && main.getConnectionDiagnostics().getEstablishedLinks() == 4,
                        20_000
                    );
                } catch (AssertionError error) {
                    describe(main);
                    for (NostrRTCRoom remote : remotes) describe(remote);
                    throw error;
                }
                RTCConnectionDiagnostics.Candidate candidate = main
                    .getConnectionDiagnostics()
                    .getCandidates()
                    .stream()
                    .filter(c -> c.getPeer().equals(probeTarget))
                    .findFirst()
                    .orElseThrow();
                NostrRTCSocket logical = main.getSocket(candidate.getPeer());
                priorities.put(candidate.getPeer().getSessionId(), Float.MAX_VALUE);
                main.setDiscoveryPriority(peer -> priorities.getOrDefault(peer.getSessionId(), 0f));
                try {
                    await(
                        () ->
                            main
                                .getConnectionDiagnostics()
                                .getCandidates()
                                .stream()
                                .anyMatch(c -> c.getPeer().equals(candidate.getPeer()) && c.getState().equals("ESTABLISHED")),
                        20_000
                    );
                } catch (AssertionError error) {
                    describe(main);
                    for (NostrRTCRoom remote : remotes) describe(remote);
                    throw error;
                }
                await(() -> main.getConnectionDiagnostics().getOccupiedResources() == 4, 5000);
                assertSame(logical, main.getSocket(candidate.getPeer()));
                assertEquals(6, main.getPeers().size());
                assertEquals(0, departures.get());
                assertEquals(4, main.getConnectionDiagnostics().getEstablishedLinks());
                assertTrue("Expected a make-before-break probe", bus.maxResources.get() == 5);
            } finally {
                main.close();
                for (NostrRTCRoom remote : remotes) remote.close();
                bus.close();
            }
        }
    }

    @Test
    public void logicalEndpointWithoutDirectLinkDeliversThroughAttestedNativeRtcRing() throws Exception {
        try (NostrKeyPair roomKeys = new NostrKeyPair()) {
            Bus bus = new Bus();
            bus.deliverTopology = true;
            List<NostrRTCRoom> rooms = new ArrayList<>();
            List<NostrRTCSignaling> signaling = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    NostrRTCRoom room = new NostrRTCRoom(settings(2), local(roomKeys, "ring-" + i), roomKeys, bus, null);
                    rooms.add(room);
                    signaling.add(bus.add(room));
                }
                for (NostrRTCSignaling signal : signaling) signal.start(true).await();
                for (NostrRTCSignaling signal : signaling) signal.sendAnnounce("").await();
                await(
                    () ->
                        rooms
                            .stream()
                            .allMatch(room ->
                                room.getPeers().size() == 3 && room.getConnectionDiagnostics().getCandidates().size() == 3
                            ),
                    5000
                );
                for (NostrRTCRoom room : rooms) room.start().await();
                try {
                    await(
                        () ->
                            rooms
                                .stream()
                                .allMatch(room ->
                                    room.getConnectionDiagnostics().getEstablishedLinks() == 2 &&
                                    room.getConnectionDiagnostics().getTopology().getEdges().size() == 4 &&
                                    room.getConnectionDiagnostics().getTopology().connectedComponents().size() == 1
                                ),
                        20_000
                    );
                } catch (AssertionError error) {
                    for (NostrRTCRoom room : rooms) describe(room);
                    throw error;
                }
                NostrRTCRoom source = rooms.get(0);
                RTCConnectionDiagnostics.Candidate opposite = source
                    .getConnectionDiagnostics()
                    .getCandidates()
                    .stream()
                    .filter(c -> !c.isPhysicalReady())
                    .findFirst()
                    .orElseThrow();
                NostrRTCPeer target = opposite.getPeer();
                NostrRTCSocket logical = source.getSocket(target);
                source.setDiscoveryPriority(peer -> peer.equals(target) ? -1f : 0f);
                await(
                    () ->
                        source
                            .getConnectionDiagnostics()
                            .getCandidates()
                            .stream()
                            .anyMatch(c -> c.getPeer().equals(target) && c.getState().equals("EXCLUDED") && c.isRoutedReady()),
                    5000
                );
                AtomicInteger unicastDeliveries = new AtomicInteger(), broadcastDeliveries = new AtomicInteger();
                for (NostrRTCRoom room : rooms) room.addMessageListener((peer, socket, channel, data, turn) -> {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    String payload = new String(bytes, StandardCharsets.UTF_8);
                    if (
                        payload.equals("routed-without-direct") &&
                        room.getLocalPeerInfo().getSessionId().equals(target.getSessionId())
                    ) unicastDeliveries.incrementAndGet();
                    if (payload.equals("ring-broadcast")) broadcastDeliveries.incrementAndGet();
                });
                source.send(target, ByteBuffer.wrap("routed-without-direct".getBytes(StandardCharsets.UTF_8))).await();
                source.broadcast(ByteBuffer.wrap("ring-broadcast".getBytes(StandardCharsets.UTF_8))).await();
                await(() -> unicastDeliveries.get() == 1 && broadcastDeliveries.get() == 3, 5000);
                assertSame(logical, source.getSocket(target));
                assertEquals(3, source.getPeers().size());
                assertFalse(logical.isPhysicalLinkEnabled());
                assertEquals(2, source.getConnectionDiagnostics().getEstablishedLinks());
                assertTrue(source.getConnectionDiagnostics().getTopologySnapshots().size() >= 4);
            } finally {
                for (NostrRTCRoom room : rooms) room.close();
                bus.close();
            }
        }
    }

    @Test
    public void simultaneousNativeOffersConvergeWithoutProtocolExtensions() throws Exception {
        try (NostrKeyPair roomKeys = new NostrKeyPair()) {
            Bus bus = new Bus();
            bus.holdOffers = true;
            NostrRTCRoom a = new NostrRTCRoom(settings(2), local(roomKeys, "legacy-a"), roomKeys, bus, null);
            NostrRTCRoom b = new NostrRTCRoom(settings(2), local(roomKeys, "legacy-b"), roomKeys, bus, null);
            NostrRTCSignaling sa = bus.add(a), sb = bus.add(b);
            try {
                a.start().await();
                b.start().await();
                sa.sendAnnounce("").await();
                sb.sendAnnounce("").await();
                await(() -> bus.heldOffers.stream().map(e -> e.getPubkey().asHex()).distinct().count() == 2, 5000);
                assertEquals(1, a.getConnectionDiagnostics().getOccupiedResources());
                assertEquals(1, b.getConnectionDiagnostics().getOccupiedResources());
                bus.holdOffers = false;
                for (SignedNostrEvent event : bus.heldOffers) bus.publish(event).await();
                await(
                    () ->
                        a.getConnectionDiagnostics().getEstablishedLinks() == 1 &&
                        b.getConnectionDiagnostics().getEstablishedLinks() == 1,
                    15_000
                );
                assertEquals("RTC", a.getConnectionDiagnostics().getCandidates().get(0).getPhysicalTransport());
            } finally {
                a.close();
                b.close();
                bus.close();
            }
        }
    }

    @Test
    public void forcedTurnUsesExistingRouteSignalingAndBidirectionalTurn() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        TurnServer server = new TurnServer(port, NostrKeyPairSigner.generate(), 10, 30);
        server.start();
        try (
            NostrKeyPair roomKeys = new NostrKeyPair();
            NostrTURNPool ta = new NostrTURNPool();
            NostrTURNPool tb = new NostrTURNPool()
        ) {
            Bus bus = new Bus();
            String url = "ws://127.0.0.1:" + server.getPort() + "/turn";
            NostrRTCLocalPeer pa = new NostrRTCLocalPeer(settings(2), NostrKeyPairSigner.generate(), "turn-a", roomKeys, url);
            NostrRTCLocalPeer pb = new NostrRTCLocalPeer(settings(2), NostrKeyPairSigner.generate(), "turn-b", roomKeys, url);
            NostrRTCRoom a = new NostrRTCRoom(settings(2).withP2pAttemptTimeout(Duration.ofSeconds(5)), pa, roomKeys, bus, ta);
            NostrRTCRoom b = new NostrRTCRoom(settings(2).withP2pAttemptTimeout(Duration.ofSeconds(1)), pb, roomKeys, bus, tb);
            NostrRTCSignaling sa = bus.add(a), sb = bus.add(b);
            a.setForceTURN(true);
            try {
                a.start().await();
                b.start().await();
                sa.sendAnnounce("").await();
                sb.sendAnnounce("").await();
                await(
                    () ->
                        a.getConnectionDiagnostics().getEstablishedLinks() == 1 &&
                        b.getConnectionDiagnostics().getEstablishedLinks() == 1,
                    15_000
                );
                assertEquals("TURN", a.getConnectionDiagnostics().getCandidates().get(0).getPhysicalTransport());
                assertFalse(a.getSockets().iterator().next().isRTCConnected());
                assertFalse(
                    "Server registration is not a delivery proof",
                    a.getSockets().iterator().next().hasProvenPhysicalTransport()
                );
                AtomicInteger delivered = new AtomicInteger();
                b.addMessageListener((p, s, c, data, turn) -> {
                    if (turn) delivered.incrementAndGet();
                });
                a.send(a.getPeers().iterator().next(), ByteBuffer.wrap(new byte[] { 1, 2, 3 })).await();
                await(() -> delivered.get() == 1, 5000);
                assertTrue(a.getSockets().iterator().next().hasProvenPhysicalTransport());

                NostrRTCChannel application = a.getSockets().iterator().next().getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME);
                Field turnSend = NostrRTCChannel.class.getDeclaredField("turnSend");
                turnSend.setAccessible(true);
                NostrTURNChannel send = (NostrTURNChannel) turnSend.get(application);
                long registration = send.getConnectionGeneration();
                send.redirectTo(url);
                java.lang.reflect.Method resurrect =
                    NostrTURNPool.class.getDeclaredMethod("resurrectChannel", NostrTURNChannel.class);
                resurrect.setAccessible(true);
                resurrect.invoke(ta, send);
                await(() -> application.isPhysicalReady() && send.getConnectionGeneration() != registration, 5000);
                assertSame("Reconnect must exercise the same handle", send, turnSend.get(application));
                assertFalse("A receipt from the previous registration is stale", application.isReplacementReady());
                a.send(a.getPeers().iterator().next(), ByteBuffer.wrap(new byte[] { 4, 5, 6 })).await();
                await(() -> delivered.get() == 2, 5000);
                assertTrue(application.isReplacementReady());
            } finally {
                a.close();
                b.close();
                bus.close();
            }
        } finally {
            server.stop();
        }
    }

    private static NostrRTCLocalPeer local(NostrKeyPair keys, String session) {
        return new NostrRTCLocalPeer(settings(16), NostrKeyPairSigner.generate(), session, keys, null);
    }

    private static void describe(NostrRTCRoom room) throws Exception {
        Field owner = NostrRTCRoom.class.getDeclaredField("physicalConnections");
        owner.setAccessible(true);
        PhysicalConnectionManager manager = (PhysicalConnectionManager) owner.get(room);
        System.err.println(
            "Local " +
            room.getLocalPeerInfo().getSessionId() +
            " resources=" +
            manager.resources() +
            " topologyEdges=" +
            room.getConnectionDiagnostics().getTopology().getEdges().size()
        );
        for (RTCConnectionDiagnostics.Candidate c : room.getConnectionDiagnostics().getCandidates()) {
            PhysicalConnectionManager.Attempt phase = manager.attempt(c.getPeer());
            System.err.println(
                c.getPeer().getSessionId() +
                " " +
                c.getState() +
                " " +
                c.getRole() +
                " protected=" +
                c.isProtectedLink() +
                " " +
                c.getPhysicalTransport() +
                " " +
                c.getOutcome() +
                " " +
                (
                    phase == null
                        ? ""
                        : phase.phase + " outgoing=" + phase.outgoing + " ready=" + phase.answerReceived + "/" + phase.outgoing
                )
            );
        }
    }

    private static void await(BooleanSupplier condition, long timeout) throws Exception {
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue("Timed out awaiting physical connection convergence", condition.getAsBoolean());
    }
}
