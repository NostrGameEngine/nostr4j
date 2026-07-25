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
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestNostrRTCSignalingRemoval {

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
            RTCSettings settings = new RTCSettings(
                Duration.ofMillis(25),
                RTCSettings.PEER_EXPIRATION,
                RTCSettings.DELAYED_CANDIDATES_INTERVAL,
                RTCSettings.ROOM_LOOP_INTERVAL,
                RTCSettings.P2P_TIMEOUT,
                RTCSettings.QUEUED_SEND_TIMEOUT,
                Duration.ofSeconds(25)
            );
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
                signer,
                Collections.emptyList(),
                "removal-test-app",
                "removal-test-protocol",
                session,
                roomKeys,
                null
            );
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

        private final CountDownLatch added = new CountDownLatch(1);
        private final CountDownLatch removed = new CountDownLatch(1);
        private volatile RemoveReason removeReason;

        @Override
        public void onAddAnnounce(NostrRTCConnectSignal announce) {
            added.countDown();
        }

        @Override
        public void onUpdateAnnounce(NostrRTCConnectSignal announce) {}

        @Override
        public void onRemoveAnnounce(NostrRTCConnectSignal announce, RemoveReason reason) {
            removeReason = reason;
            removed.countDown();
        }

        @Override
        public void onReceiveOffer(NostrRTCOfferSignal offer) {}

        @Override
        public void onReceiveAnswer(NostrRTCAnswerSignal answer) {}

        @Override
        public void onReceiveCandidates(NostrRTCRouteSignal candidate) {}
    }
}
