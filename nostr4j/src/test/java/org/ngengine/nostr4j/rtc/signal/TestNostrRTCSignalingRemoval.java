/*
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.nostr4j.rtc.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestNostrRTCSignalingRemoval {

    @Test
    public void storedDiscoveryKeepsOnlyNewestPresencePerPeerAndSession() throws Exception {
        Fixture fixture = new Fixture();
        try {
            Instant now = Instant.now();
            SignedNostrEvent newest = fixture.presence(fixture.remotePeer, now.minusSeconds(2), now.plusSeconds(80), false);
            SignedNostrEvent old = fixture.presence(fixture.remotePeer, now.minusSeconds(10), now.plusSeconds(40), false);
            NostrRTCLocalPeer secondSession = fixture.peer(fixture.remoteSigner, "second-session");
            fixture.signaling.onSubEvent(old, true);
            fixture.signaling.onSubEvent(newest, true);
            fixture.signaling.onSubEvent(old, true);
            fixture.signaling.onSubEvent(
                fixture.presence(secondSession, now.minusSeconds(1), now.plusSeconds(50), false),
                true
            );
            fixture.awaitIdle();
            assertTrue(fixture.signaling.getAnnounces().isEmpty());
            fixture.signaling.onDiscoveryEose();
            assertEquals(2, fixture.signaling.getAnnounces().size());
            assertEquals(2, fixture.listener.addCount.get());
            assertEquals(
                newest.getExpiration(),
                fixture.signaling
                    .getAnnounces()
                    .stream()
                    .filter(a -> a.getPeer().getSessionId().equals(fixture.remotePeer.getSessionId()))
                    .findFirst()
                    .get()
                    .getExpireAt()
            );
        } finally {
            fixture.signaling.close();
        }
    }

    @Test
    public void newestStoredDisconnectPreventsResurrectionIncludingSameSecondTies() throws Exception {
        for (boolean sameSecond : new boolean[] { false, true }) {
            Fixture fixture = new Fixture();
            try {
                Instant now = Instant.now();
                SignedNostrEvent connect = fixture.presence(
                    fixture.remotePeer,
                    now.minusSeconds(5),
                    now.plusSeconds(60),
                    false
                );
                SignedNostrEvent disconnect = fixture.presence(
                    fixture.remotePeer,
                    sameSecond ? connect.getCreatedAt() : now.minusSeconds(2),
                    null,
                    true
                );
                fixture.signaling.onSubEvent(disconnect, true);
                fixture.signaling.onSubEvent(connect, true);
                fixture.signaling.onDiscoveryEose(); // May race signature verification tasks.
                fixture.awaitIdle();
                assertTrue(fixture.signaling.getAnnounces().isEmpty());
                assertEquals(0, fixture.listener.addCount.get());
                fixture.signaling.onSubEvent(connect, true); // Another relay returns an older batch.
                fixture.awaitIdle();
                assertTrue(fixture.signaling.getAnnounces().isEmpty());
            } finally {
                fixture.signaling.close();
            }
        }
    }

    @Test
    public void olderStoredDisconnectCannotRemoveNewerLivePresence() throws Exception {
        Fixture fixture = new Fixture();
        try {
            Instant now = Instant.now();
            fixture.signaling.onSubEvent(
                fixture.presence(fixture.remotePeer, now.minusSeconds(2), now.plusSeconds(60), false),
                false
            );
            assertTrue(fixture.listener.added.await(2, TimeUnit.SECONDS));
            fixture.signaling.onSubEvent(fixture.presence(fixture.remotePeer, now.minusSeconds(10), null, true), true);
            fixture.signaling.onDiscoveryEose();
            fixture.awaitIdle();
            assertEquals(1, fixture.signaling.getAnnounces().size());
            assertEquals(0, fixture.listener.removeCount.get());
        } finally {
            fixture.signaling.close();
        }
    }

    @Test
    public void storedDiscoveryRejectsExpiredFutureAndUnauthorizedPresence() throws Exception {
        Fixture fixture = new Fixture();
        try {
            Instant now = Instant.now();
            fixture.signaling.onSubEvent(
                fixture.presence(fixture.remotePeer, now.minusSeconds(5), now.minusSeconds(1), false),
                true
            );
            fixture.signaling.onSubEvent(
                fixture.presence(fixture.remotePeer, now.plusSeconds(60), now.plusSeconds(120), false),
                true
            );
            UnsignedNostrEvent forged = new UnsignedNostrEvent(new HashMap<>(fixture.connectEvent().toMap()));
            forged.replaceTag("roomproof", "00", "00");
            fixture.signaling.onSubEvent(fixture.remoteSigner.sign(forged).await(), true);
            fixture.awaitIdle();
            fixture.signaling.onDiscoveryEose();
            assertTrue(fixture.signaling.getAnnounces().isEmpty());
            fixture.signaling.onSubEvent(fixture.connectEvent(), true);
            fixture.awaitIdle();
            assertEquals(1, fixture.signaling.getAnnounces().size());
        } finally {
            fixture.signaling.close();
        }
    }

    @Test
    public void storedOffersDoNotReplayOldNegotiations() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SignedNostrEvent offer = new NostrRTCOfferSignal(
                fixture.remoteSigner,
                fixture.roomKeys,
                fixture.remotePeer,
                "old SDP"
            )
                .toEvent(fixture.localPeer.getPubkey())
                .await();
            fixture.signaling.onSubEvent(offer, true);
            fixture.signaling.onDiscoveryEose();
            fixture.awaitIdle();
            assertEquals(0, fixture.listener.offerCount.get());
        } finally {
            fixture.signaling.close();
        }
    }

    @Test
    public void discoveryFilterRequestsBoundedHistoryAcrossAllPeers() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.signaling.start(false).await();
            Field field = NostrRTCSignaling.class.getDeclaredField("discoverySub");
            field.setAccessible(true);
            NostrSubscription sub = (NostrSubscription) field.get(fixture.signaling);
            assertNull(sub.getFilters().iterator().next().getSince());
            assertEquals(Integer.valueOf(2048), sub.getFilters().iterator().next().getLimit());
        } finally {
            fixture.signaling.close();
        }
    }

    @Test
    public void disconnectEventRemovesCopyOnWriteAnnouncement() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.signaling.onSubEvent(fixture.connectEvent(), false);
            assertTrue(fixture.listener.added.await(2, TimeUnit.SECONDS));
            assertEquals(1, fixture.signaling.getAnnounces().size());

            fixture.signaling.onSubEvent(fixture.disconnectEvent(), false);

            assertTrue(fixture.listener.removed.await(2, TimeUnit.SECONDS));
            assertEquals(NostrRTCSignaling.Listener.RemoveReason.DISCONNECTED, fixture.listener.removeReason);
            assertTrue(fixture.signaling.getAnnounces().isEmpty());
        } finally {
            fixture.signaling.close();
        }
    }

    @Test
    public void expirationLoopRemovesCopyOnWriteAnnouncement() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.signaling.onSubEvent(fixture.connectEvent(), false);
            assertTrue(fixture.listener.added.await(2, TimeUnit.SECONDS));
            NostrRTCConnectSignal announce = fixture.signaling.getAnnounces().iterator().next();
            announce.updateExpireAt(Instant.now().minusSeconds(1));

            fixture.signaling.start(false).await();

            assertTrue(fixture.listener.removed.await(2, TimeUnit.SECONDS));
            assertEquals(NostrRTCSignaling.Listener.RemoveReason.EXPIRED, fixture.listener.removeReason);
            assertTrue(fixture.signaling.getAnnounces().isEmpty());
        } finally {
            fixture.signaling.close();
        }
    }

    private static final class Fixture {

        private final NostrKeyPair roomKeys = new NostrKeyPair();
        private final NostrKeyPairSigner localSigner = new NostrKeyPairSigner(new NostrKeyPair());
        private final NostrKeyPairSigner remoteSigner = new NostrKeyPairSigner(new NostrKeyPair());
        private final NostrRTCLocalPeer localPeer = peer(localSigner, "local-session");
        private final NostrRTCLocalPeer remotePeer = peer(remoteSigner, "remote-session");
        private final RecordingListener listener = new RecordingListener();
        private final NostrRTCSignaling signaling;

        private Fixture() {
            RTCSettings settings = RTCSettings
                .getDefault("removal-test-app", "removal-test-protocol")
                .withSignalingLoopInterval(Duration.ofMillis(25))
                .withSignalingAnnounceExpiration(Duration.ofSeconds(25));
            signaling =
                new NostrRTCSignaling(
                    settings,
                    "removal-test-app",
                    "removal-test-protocol",
                    localPeer,
                    roomKeys,
                    new NostrPool()
                );
            signaling.addListener(listener);
        }

        private NostrRTCLocalPeer peer(NostrKeyPairSigner signer, String session) {
            return new NostrRTCLocalPeer(
                RTCSettings.getDefault("removal-test-app", "removal-test-protocol").withStunServers(Collections.emptyList()),
                signer,
                session,
                roomKeys,
                null
            );
        }

        private void awaitIdle() throws Exception {
            Field field = NostrRTCSignaling.class.getDeclaredField("pendingSignals");
            field.setAccessible(true);
            AtomicInteger pending = (AtomicInteger) field.get(signaling);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (pending.get() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals("signaling did not drain", 0, pending.get());
            // Ensure a requested EOSE flush completed after the final worker decremented the counter.
            Field ready = NostrRTCSignaling.class.getDeclaredField("storedPresenceReady");
            ready.setAccessible(true);
            if (ready.getBoolean(signaling)) signaling.onDiscoveryEose();
        }

        private SignedNostrEvent presence(NostrRTCLocalPeer peer, Instant createdAt, Instant expiration, boolean disconnect)
            throws Exception {
            NostrRTCSignal signal = disconnect
                ? new NostrRTCDisconnectSignal(remoteSigner, roomKeys, peer, "")
                : new NostrRTCConnectSignal(remoteSigner, roomKeys, peer, expiration, "");
            UnsignedNostrEvent event = new UnsignedNostrEvent(new HashMap<>(signal.toEvent(null).await().toMap()));
            event.createdAt(createdAt).withExpiration(expiration).clearTags("roomproof");
            return signal.signForRoom(event).await();
        }

        private org.ngengine.nostr4j.event.SignedNostrEvent connectEvent() throws Exception {
            return new NostrRTCConnectSignal(remoteSigner, roomKeys, remotePeer, Instant.now().plusSeconds(30), "")
                .toEvent(null)
                .await();
        }

        private org.ngengine.nostr4j.event.SignedNostrEvent disconnectEvent() throws Exception {
            return new NostrRTCDisconnectSignal(remoteSigner, roomKeys, remotePeer, "").toEvent(null).await();
        }
    }

    private static final class RecordingListener implements NostrRTCSignaling.Listener {

        private final AtomicInteger addCount = new AtomicInteger();
        private final AtomicInteger removeCount = new AtomicInteger();
        private final AtomicInteger offerCount = new AtomicInteger();
        private final CountDownLatch added = new CountDownLatch(1);
        private final CountDownLatch removed = new CountDownLatch(1);
        private volatile RemoveReason removeReason;

        @Override
        public void onAddAnnounce(NostrRTCConnectSignal announce) {
            addCount.incrementAndGet();
            added.countDown();
        }

        @Override
        public void onUpdateAnnounce(NostrRTCConnectSignal announce) {}

        @Override
        public void onRemoveAnnounce(NostrRTCConnectSignal announce, RemoveReason reason) {
            removeCount.incrementAndGet();
            removeReason = reason;
            removed.countDown();
        }

        @Override
        public void onReceiveOffer(NostrRTCOfferSignal offer) {
            offerCount.incrementAndGet();
        }

        @Override
        public void onReceiveAnswer(NostrRTCAnswerSignal answer) {}

        @Override
        public void onReceiveCandidates(NostrRTCRouteSignal candidate) {}
    }
}
