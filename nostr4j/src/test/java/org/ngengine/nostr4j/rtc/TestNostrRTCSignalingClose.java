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

package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;

public class TestNostrRTCSignalingClose {

    @Test
    public void announceUsesConfiguredSignalingExpiration() throws Exception {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(
            new NostrKeyPairSigner(new NostrKeyPair()),
            Collections.emptyList(),
            "expiration-test-app",
            "expiration-test-protocol",
            "expiration-test-session",
            roomKeyPair,
            null
        );
        CapturingPool pool = new CapturingPool();
        RTCSettings settings = new RTCSettings(
            RTCSettings.SIGNALING_LOOP_INTERVAL,
            RTCSettings.PEER_EXPIRATION,
            RTCSettings.DELAYED_CANDIDATES_INTERVAL,
            RTCSettings.ROOM_LOOP_INTERVAL,
            RTCSettings.P2P_TIMEOUT,
            RTCSettings.QUEUED_SEND_TIMEOUT,
            Duration.ofSeconds(25)
        );
        NostrRTCSignaling signaling = new NostrRTCSignaling(
            settings,
            "expiration-test-app",
            "expiration-test-protocol",
            localPeer,
            roomKeyPair,
            pool
        );

        try {
            signaling.start(true).await();
            signaling.sendAnnounce("test").await();

            SignedNostrEvent published = pool.published.get();
            assertNotNull(published);
            assertEquals("connect", published.getFirstTagFirstValue("t"));
            long remainingSeconds = Duration.between(Instant.now(), published.getExpiration()).toSeconds();
            assertTrue("Announcement expires too early: " + remainingSeconds, remainingSeconds >= 23);
            assertTrue("Announcement expires too late: " + remainingSeconds, remainingSeconds <= 25);
        } finally {
            signaling.close();
        }
    }

    @Test
    public void closeWaitsUntilDisconnectIsPublished() throws Exception {
        CountDownLatch signStarted = new CountDownLatch(1);
        CountDownLatch allowSign = new CountDownLatch(1);
        DelayedSigner signer = new DelayedSigner(new NostrKeyPair(), signStarted, allowSign);
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(
            signer,
            Collections.emptyList(),
            "close-test-app",
            "close-test-protocol",
            "close-test-session",
            roomKeyPair,
            null
        );
        CapturingPool pool = new CapturingPool();
        NostrRTCSignaling signaling = new NostrRTCSignaling(
            RTCSettings.DEFAULT,
            "close-test-app",
            "close-test-protocol",
            localPeer,
            roomKeyPair,
            pool
        );

        Thread closeThread = new Thread(signaling::close, "rtc-signaling-close-test");
        closeThread.start();

        assertTrue("Disconnect signing did not start", signStarted.await(2, TimeUnit.SECONDS));
        assertTrue("close() returned before disconnect signing completed", closeThread.isAlive());
        assertNull("Disconnect was published before signing completed", pool.published.get());

        allowSign.countDown();
        closeThread.join(5_000L);

        assertFalse("close() did not finish after disconnect publication", closeThread.isAlive());
        SignedNostrEvent published = pool.published.get();
        assertNotNull("Disconnect event was not handed to the relay pool", published);
        assertEquals("disconnect", published.getFirstTagFirstValue("t"));
    }

    private static final class CapturingPool extends NostrPool {

        private final AtomicReference<SignedNostrEvent> published = new AtomicReference<SignedNostrEvent>();

        @Override
        public List<AsyncTask<NostrMessageAck>> publish(SignedNostrEvent event) {
            published.set(event);
            return Collections.emptyList();
        }
    }

    private static final class DelayedSigner extends NostrKeyPairSigner {

        private final CountDownLatch signStarted;
        private final CountDownLatch allowSign;

        private DelayedSigner(NostrKeyPair keyPair, CountDownLatch signStarted, CountDownLatch allowSign) {
            super(keyPair);
            this.signStarted = signStarted;
            this.allowSign = allowSign;
        }

        @Override
        public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
            return AsyncTask.create((resolve, reject) -> {
                Thread worker = new Thread(
                    () -> {
                        signStarted.countDown();
                        try {
                            if (!allowSign.await(5, TimeUnit.SECONDS)) {
                                reject.accept(new AssertionError("Timed out waiting to release signer"));
                                return;
                            }
                            DelayedSigner.super
                                .sign(event)
                                .catchException(reject)
                                .then(signed -> {
                                    resolve.accept(signed);
                                    return null;
                                });
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            reject.accept(e);
                        }
                    },
                    "delayed-nostr-signer-test"
                );
                worker.setDaemon(true);
                worker.start();
            });
        }
    }
}
