/* BSD 3-Clause License - Copyright (c) 2026, Riccardo Balbo */
package org.ngengine.nostr4j.rtc.signal;

import static org.junit.Assert.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrRTCRoom;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;

public class TestRTCSettingsPeerConfiguration {

    @Test
    public void settingsReachLocalPeerAndSurviveOtherWithCalls() throws Exception {
        RTCSettings settings = RTCSettings.getDefault("example.app", "example.protocol")
            .withStunServers(List.of("stun.example.invalid:3478"))
            .withQueuedSendTimeout(Duration.ofSeconds(4))
            .withMaxDirectPeers(3);

        try (NostrKeyPair identity = new NostrKeyPair(); NostrKeyPair room = new NostrKeyPair()) {
            NostrKeyPairSigner signer = new NostrKeyPairSigner(identity);
            NostrRTCLocalPeer peer = new NostrRTCLocalPeer(
                settings, signer, "example-session", room, "wss://example.invalid/turn"
            );
            assertEquals("example.app", peer.getApplicationId());
            assertEquals("example.protocol", peer.getProtocolId());
            assertEquals("example-session", peer.getSessionId());
            assertEquals("wss://example.invalid/turn", peer.getTurnServer());
            assertEquals(List.of("stun.example.invalid:3478"), peer.getStunServers());
        }

        assertEquals("example.app", RTCSettings.getDefault("example.app", "example.protocol").getApplicationId());
        assertEquals("example.protocol", RTCSettings.getDefault("example.app", "example.protocol").getProtocolId());
        assertEquals(settings, settings.clone());
        assertEquals(settings.hashCode(), settings.clone().hashCode());
    }

    @Test
    public void sessionIdsArePassedSeparatelyFromSharedSettings() throws Exception {
        RTCSettings settings = RTCSettings.getDefault("example.app", "example.protocol");
        String firstSessionId = NostrRTCLocalPeer.newSessionId();
        String secondSessionId = NostrRTCLocalPeer.newSessionId();
        assertNotEquals(firstSessionId, secondSessionId);

        try (
            NostrKeyPair firstIdentity = new NostrKeyPair();
            NostrKeyPair secondIdentity = new NostrKeyPair();
            NostrKeyPair thirdIdentity = new NostrKeyPair();
            NostrKeyPair room = new NostrKeyPair()
        ) {
            NostrRTCLocalPeer first = new NostrRTCLocalPeer(
                settings, new NostrKeyPairSigner(firstIdentity), firstSessionId, room, null
            );
            NostrRTCLocalPeer second = new NostrRTCLocalPeer(
                settings, new NostrKeyPairSigner(secondIdentity), secondSessionId, room, null
            );
            NostrRTCLocalPeer generated = new NostrRTCLocalPeer(
                settings, new NostrKeyPairSigner(thirdIdentity), room, null
            );
            assertEquals(firstSessionId, first.getSessionId());
            assertEquals(secondSessionId, second.getSessionId());
            assertNotEquals(first.getSessionId(), second.getSessionId());
            assertNotEquals(first.getSessionId(), generated.getSessionId());
            assertNotEquals(second.getSessionId(), generated.getSessionId());
            assertThrows(
                NullPointerException.class,
                () -> new NostrRTCLocalPeer(settings, new NostrKeyPairSigner(firstIdentity), null, room, null)
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> new NostrRTCLocalPeer(settings, new NostrKeyPairSigner(firstIdentity), " ", room, null)
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> new NostrRTCLocalPeer(settings, new NostrKeyPairSigner(firstIdentity), firstSessionId, room, " ")
            );
        }

        assertThrows(NullPointerException.class, () -> RTCSettings.getDefault(null, "example.protocol"));
        assertThrows(NullPointerException.class, () -> RTCSettings.getDefault("example.app", null));
        assertThrows(IllegalArgumentException.class, () -> RTCSettings.getDefault(" ", "example.protocol"));
        assertThrows(IllegalArgumentException.class, () -> RTCSettings.getDefault("example.app", " "));
    }

    @Test
    public void signalingRelaysAreCopiedAndSurviveOtherWithCalls() {
        RTCSettings defaults = RTCSettings.getDefault("example.app", "example.protocol");
        assertEquals(List.copyOf(RTCSettings.DEFAULT_SIGNALING_RELAYS), defaults.getSignalingRelays());

        List<String> relays = new ArrayList<>(List.of("wss://relay.example.invalid"));
        RTCSettings settings = defaults.withSignalingRelays(relays).withMaxDirectPeers(3);
        relays.clear();
        assertEquals(List.of("wss://relay.example.invalid"), settings.getSignalingRelays());
        assertEquals(settings, settings.clone());
        assertNotEquals(defaults, settings);
        assertThrows(UnsupportedOperationException.class, () -> settings.getSignalingRelays().clear());
        assertThrows(IllegalArgumentException.class, () -> defaults.withSignalingRelays(List.of(" ")));
    }

    @Test
    public void settingsRejectNullValuesAndDoNotExposeMutableStunServers() {
        RTCSettings settings = RTCSettings.getDefault("example.app", "example.protocol");
        assertThrows(NullPointerException.class, () -> settings.withSignalingLoopInterval(null));
        assertThrows(NullPointerException.class, () -> settings.withSignalingAnnounceExpiration(null));
        assertThrows(NullPointerException.class, () -> settings.withPeerExpiration(null));
        assertThrows(NullPointerException.class, () -> settings.withDelayedCandidatesInterval(null));
        assertThrows(NullPointerException.class, () -> settings.withRoomLoopInterval(null));
        assertThrows(NullPointerException.class, () -> settings.withP2pAttemptTimeout(null));
        assertThrows(NullPointerException.class, () -> settings.withQueuedSendTimeout(null));
        assertThrows(NullPointerException.class, () -> settings.withStunServers(null));
        assertThrows(NullPointerException.class, () -> settings.withStunServer(null));
        assertThrows(IllegalArgumentException.class, () -> settings.withStunServer(" "));
        assertThrows(IllegalArgumentException.class, () -> settings.withStunServers(List.of("")));
        assertThrows(IllegalArgumentException.class, () -> settings.withPeerExpiration(Duration.ofSeconds(-1)));
        assertThrows(UnsupportedOperationException.class, () -> settings.getStunServers().clear());
    }

    @Test
    public void roomEnsuresConfiguredRelaysOnSuppliedPool() throws Exception {
        List<String> ensured = new ArrayList<>();
        NostrPool pool = new NostrPool() {
            @Override
            public AsyncTask<NostrRelay> ensureRelay(String relay) {
                ensured.add(relay);
                return AsyncTask.completed(null);
            }
        };
        RTCSettings settings = RTCSettings.getDefault("example.app", "example.protocol")
            .withSignalingRelays(List.of("wss://one.example.invalid", "wss://two.example.invalid"));
        try (NostrKeyPair identity = new NostrKeyPair(); NostrKeyPair roomKey = new NostrKeyPair()) {
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                settings, new NostrKeyPairSigner(identity), "example-session", roomKey, null
            );
            NostrRTCRoom room = new NostrRTCRoom(settings, local, roomKey, pool, null);
            try {
                assertEquals(settings.getSignalingRelays(), ensured);
            } finally {
                room.close();
            }
        }
    }
}
