/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

public class TestNostrRTCLogicalSockets {

    @Test
    public void testEveryAnnouncementCreatesStableLogicalSocketBeforePhysicalConnection() throws Exception {
        NostrKeyPair roomKeys = new NostrKeyPair();
        NostrRTCLocalPeer local = localPeer("local-session", roomKeys);
        NostrRTCRoom room = new NostrRTCRoom(
            RTCSettings.getDefault("logical-app", "logical-protocol").withSignalingRelays(java.util.List.of()),
            local,
            roomKeys,
            new NostrPool(),
            null
        );
        AtomicInteger availableSockets = new AtomicInteger();
        room.addPeerSocketAvailableListener((peer, socket) -> availableSockets.incrementAndGet());
        try {
            NostrRTCConnectSignal first = announce("remote-one", roomKeys);
            NostrRTCConnectSignal second = announce("remote-two", roomKeys);

            deliverAnnouncement(room, first);
            deliverAnnouncement(room, second);
            deliverAnnouncement(room, first);

            assertEquals(2, room.getPeers().size());
            assertEquals(2, room.getSockets().size());
            assertEquals(2, availableSockets.get());
            NostrRTCSocket firstSocket = room.getSocket(first.getPeer());
            assertNotNull(firstSocket);
            NostrRTCChannel defaultChannel = firstSocket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME);
            assertNotNull(defaultChannel);

            firstSocket.setPhysicalLinkEnabled(false);
            assertFalse(firstSocket.isPhysicalLinkEnabled());
            assertSame(firstSocket, room.getSocket(first.getPeer()));
            assertSame(defaultChannel, firstSocket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME));
            assertFalse(defaultChannel.isReady());
            try {
                firstSocket.listen();
                fail("a routed-only logical socket must not initiate a physical offer");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Error while listening"));
                assertNotNull(expected.getCause());
                assertTrue(expected.getCause().getMessage().contains("Physical peer link is disabled"));
            }
        } finally {
            room.close();
        }
    }

    @Test
    public void testLargeRoomKeepsAllLogicalSocketsWhileCappingPhysicalLinks() throws Exception {
        NostrKeyPair roomKeys = new NostrKeyPair();
        NostrRTCLocalPeer local = localPeer("local-large-session", roomKeys);
        NostrRTCRoom room = new NostrRTCRoom(
            RTCSettings
                .getDefault("logical-app", "logical-protocol")
                .withSignalingRelays(java.util.List.of())
                .withMaxDirectPeers(16),
            local,
            roomKeys,
            new NostrPool(),
            null
        );
        try {
            for (int index = 0; index < 64; index++) {
                deliverAnnouncement(room, announce("remote-large-" + index, roomKeys));
            }

            assertEquals(64, room.getPeers().size());
            assertEquals(64, room.getSockets().size());
            int physicalLinks = 0;
            for (NostrRTCSocket socket : room.getSockets()) {
                if (socket.isPhysicalLinkEnabled()) {
                    physicalLinks++;
                }
                assertNotNull(socket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME));
            }
            assertTrue("physical direct links must respect maxDirectPeers", physicalLinks <= 16);
            assertEquals("discovery alone reserves no physical connection resources", 0, physicalLinks);
        } finally {
            room.close();
        }
    }

    @Test
    public void testApplicationApisRejectReservedRoutingChannels() throws Exception {
        NostrKeyPair roomKeys = new NostrKeyPair();
        NostrRTCLocalPeer local = localPeer("local-reserved-session", roomKeys);
        NostrRTCRoom room = new NostrRTCRoom(
            RTCSettings.getDefault("logical-app", "logical-protocol").withSignalingRelays(java.util.List.of()),
            local,
            roomKeys,
            new NostrPool(),
            null
        );
        try {
            NostrRTCConnectSignal remote = announce("remote-reserved", roomKeys);
            deliverAnnouncement(room, remote);
            NostrRTCSocket socket = room.getSocket(remote.getPeer());
            NostrRTCChannel internal = socket.getChannel(InternalRoutingChannels.CONTROL);
            assertNotNull(internal);

            assertReserved(() -> room.createChannel(remote.getPeer(), InternalRoutingChannels.CONTROL));
            assertReserved(() -> room.send(InternalRoutingChannels.CONTROL, remote.getPeer(), ByteBuffer.allocate(0)));
            assertReserved(() -> room.send(internal, ByteBuffer.allocate(0)));
            assertReserved(() -> room.broadcast(InternalRoutingChannels.CONTROL, ByteBuffer.allocate(0)));

            List<String> visibleChannels = new ArrayList<String>();
            socket.addListener(new RecordingSocketListener(visibleChannels));
            room.createChannel(remote.getPeer(), "application-profile", true, false, Integer.valueOf(2), null);
            assertEquals(List.of("application-profile"), visibleChannels);
        } finally {
            room.close();
        }
    }

    @Test
    public void testCloseIsIdempotentAndReleasesLogicalRoomState() throws Exception {
        NostrKeyPair roomKeys = new NostrKeyPair();
        NostrRTCRoom room = new NostrRTCRoom(
            RTCSettings.getDefault("logical-app", "logical-protocol").withSignalingRelays(java.util.List.of()),
            localPeer("local-close-session", roomKeys),
            roomKeys,
            new NostrPool(),
            null
        );
        deliverAnnouncement(room, announce("remote-close-session", roomKeys));
        assertEquals(1, room.getPeers().size());

        room.close();
        room.close();

        assertTrue(room.getPeers().isEmpty());
        assertTrue(room.getSockets().isEmpty());
    }

    @Test
    public void testExpiredAnnouncementNotifiesPeerDisconnection() throws Exception {
        NostrKeyPair roomKeys = new NostrKeyPair();
        NostrRTCRoom room = new NostrRTCRoom(
            RTCSettings.getDefault("logical-app", "logical-protocol").withSignalingRelays(List.of()),
            localPeer("local-expiration-session", roomKeys),
            roomKeys,
            new NostrPool(),
            null
        );
        AtomicInteger disconnected = new AtomicInteger();
        room.addDisconnectionListener((peer, socket) -> disconnected.incrementAndGet());
        try {
            NostrRTCConnectSignal remote = announce("remote-expiration-session", roomKeys);
            deliverAnnouncement(room, remote);
            assertEquals(1, room.getPeers().size());

            Method remove =
                NostrRTCRoom.class.getDeclaredMethod(
                        "onRemoveAnnounce",
                        NostrRTCConnectSignal.class,
                        NostrRTCSignaling.Listener.RemoveReason.class
                    );
            remove.setAccessible(true);
            remove.invoke(room, remote, NostrRTCSignaling.Listener.RemoveReason.EXPIRED);

            assertTrue(room.getPeers().isEmpty());
            assertEquals(1, disconnected.get());
        } finally {
            room.close();
        }
    }

    private static void assertReserved(Runnable operation) {
        try {
            operation.run();
            fail("Expected reserved routing channel rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reserved"));
        }
    }

    private static final class RecordingSocketListener implements NostrRTCSocketListener {

        private final List<String> channels;

        private RecordingSocketListener(List<String> channels) {
            this.channels = channels;
        }

        @Override
        public void onRTCSocketRouteUpdate(
            NostrRTCSocket socket,
            Collection<RTCTransportIceCandidate> candidates,
            String turnServer
        ) {}

        @Override
        public void onRTCSocketClose(NostrRTCSocket socket) {}

        @Override
        public void onRTCChannelReady(NostrRTCChannel channel) {}

        @Override
        public void onRTCChannel(NostrRTCChannel channel) {
            channels.add(channel.getName());
        }
    }

    private static NostrRTCConnectSignal announce(String sessionId, NostrKeyPair roomKeys) {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrRTCPeer peer = new NostrRTCPeer(
            NGEUtils.awaitNoThrow(signer.getPublicKey()),
            "logical-app",
            "logical-protocol",
            sessionId,
            roomKeys.getPublicKey(),
            null
        );
        return new NostrRTCConnectSignal(signer, roomKeys, peer, Instant.now().plusSeconds(60), "");
    }

    private static NostrRTCLocalPeer localPeer(String sessionId, NostrKeyPair roomKeys) {
        return new NostrRTCLocalPeer(
            RTCSettings
                .getDefault("logical-app", "logical-protocol")
                .withSignalingRelays(java.util.List.of())
                .withStunServers(Collections.emptyList()),
            NostrKeyPairSigner.generate(),
            sessionId,
            roomKeys,
            null
        );
    }

    private static void deliverAnnouncement(NostrRTCRoom room, NostrRTCConnectSignal announce) throws Exception {
        Method method = NostrRTCRoom.class.getDeclaredMethod("onAddAnnounce", NostrRTCConnectSignal.class);
        method.setAccessible(true);
        method.invoke(room, announce);
    }
}
