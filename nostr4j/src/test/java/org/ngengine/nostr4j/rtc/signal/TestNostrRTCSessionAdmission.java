/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.RTCSessionLimits;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Signed ingress, bounded identity watermarks and explicit-time expiry; no relay or native RTC.
 */
public class TestNostrRTCSessionAdmission {

    @Test
    public void authenticatedPresenceFloodIsBoundedPerIdentityWithoutEvictingLiveSessions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Instant created = Instant.now().minusSeconds(10L);
            for (int index = 0; index < 80; index++) {
                fixture.deliver(fixture.remote, "session-" + index, created, Instant.now().plusSeconds(60L));
            }
            assertEquals(RTCSessionLimits.MAX_ANNOUNCED_SESSIONS_PER_IDENTITY, fixture.signaling.getAnnounces().size());
            assertTrue(fixture.watermarks().size() <= RTCSessionLimits.MAX_PRESENCE_RECORDS_PER_IDENTITY);
            for (NostrRTCConnectSignal announce : fixture.signaling.getAnnounces()) {
                int index = Integer.parseInt(announce.getPeer().getSessionId().substring("session-".length()));
                assertTrue(
                    "New sessions must not evict live admitted ones",
                    index < RTCSessionLimits.MAX_ANNOUNCED_SESSIONS_PER_IDENTITY
                );
            }
            NostrKeyPairSigner other = NostrKeyPairSigner.generate();
            fixture.deliver(other, "legitimate-other-author", created, Instant.now().plusSeconds(60L));
            assertEquals(RTCSessionLimits.MAX_ANNOUNCED_SESSIONS_PER_IDENTITY + 1, fixture.signaling.getAnnounces().size());
        }
    }

    @Test
    public void expiryReopensIdentityAdmissionForAFreshAnnouncement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Instant now = Instant.now();
            for (int index = 0; index < RTCSessionLimits.MAX_ANNOUNCED_SESSIONS_PER_IDENTITY; index++) {
                fixture.deliver(fixture.remote, "live-" + index, now.minusSeconds(10L), now.plusSeconds(60L));
            }
            fixture.deliver(fixture.remote, "next", now.minusSeconds(9L), now.plusSeconds(60L));
            assertEquals(RTCSessionLimits.MAX_ANNOUNCED_SESSIONS_PER_IDENTITY, fixture.signaling.getAnnounces().size());
            Method prune = NostrRTCSignaling.class.getDeclaredMethod("pruneExpiredPresences", Instant.class);
            prune.setAccessible(true);
            synchronized (fixture.get("presenceLock")) {
                prune.invoke(fixture.signaling, now.plusSeconds(61L));
            }
            assertTrue(fixture.signaling.getAnnounces().isEmpty());
            fixture.deliver(fixture.remote, "next", now.minusSeconds(1L), now.plusSeconds(500L));
            assertEquals(1, fixture.signaling.getAnnounces().size());
            assertEquals("next", fixture.signaling.getAnnounces().iterator().next().getPeer().getSessionId());
        }
    }

    @Test
    public void oversizedSessionIdCannotRetainPresenceOrWatermarks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.deliver(
                fixture.remote,
                "x".repeat(RTCSessionLimits.MAX_SESSION_ID_CHARS + 1),
                Instant.now().minusSeconds(1L),
                Instant.now().plusSeconds(60L)
            );
            assertTrue(fixture.signaling.getAnnounces().isEmpty());
            assertTrue(fixture.watermarks().isEmpty());
            fixture.deliver(fixture.remote, "normal", Instant.now().minusSeconds(1L), Instant.now().plusSeconds(60L));
            assertEquals(1, fixture.signaling.getAnnounces().size());
        }
    }

    @Test
    public void configuredQuotasApplyToSignedIngressAndPresenceWatermarks() throws Exception {
        RTCSessionLimits limits = new RTCSessionLimits(16, 4, 12, 14, java.time.Duration.ofSeconds(30));
        try (Fixture fixture = new Fixture(limits)) {
            for (int index = 0; index < 40; index++) {
                fixture.deliver(
                    fixture.remote,
                    "configured-" + index,
                    Instant.now().minusSeconds(1),
                    Instant.now().plusSeconds(60)
                );
            }
            assertEquals(12, fixture.signaling.getAnnounces().size());
            assertTrue(fixture.watermarks().size() <= 14);
        }
    }

    private static final class Fixture implements AutoCloseable {

        final NostrKeyPair keys = new NostrKeyPair();
        final NostrKeyPairSigner remote = NostrKeyPairSigner.generate();
        final NostrPool pool = new NostrPool();
        final NostrRTCSignaling signaling;
        final AsyncExecutor originalExecutor;

        Fixture() throws Exception {
            this(RTCSessionLimits.getDefault());
        }

        Fixture(RTCSessionLimits limits) throws Exception {
            RTCSettings settings = RTCSettings
                .getDefault("session-admission-app", "session-admission-protocol")
                .withStunServers(List.of())
                .withSignalingRelays(List.of())
                .withSessionLimits(limits);
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(settings, NostrKeyPairSigner.generate(), "local", keys, null);
            signaling =
                new NostrRTCSignaling(settings, settings.getApplicationId(), settings.getProtocolId(), local, keys, pool);
            Field executor = NostrRTCSignaling.class.getDeclaredField("executor");
            executor.setAccessible(true);
            originalExecutor = (AsyncExecutor) executor.get(signaling);
            executor.set(signaling, new InlineExecutor());
        }

        void deliver(NostrKeyPairSigner signer, String session, Instant created, Instant expires) throws Exception {
            NostrRTCPeer peer = new NostrRTCPeer(
                NGEUtils.awaitNoThrow(signer.getPublicKey()),
                "session-admission-app",
                "session-admission-protocol",
                session,
                keys.getPublicKey(),
                null
            );
            NostrRTCConnectSignal signal = new NostrRTCConnectSignal(signer, keys, peer, expires, "");
            UnsignedNostrEvent unsigned = new UnsignedNostrEvent(new HashMap<>(signal.toEvent(null).await().toMap()));
            unsigned.createdAt(created).clearTags("roomproof");
            SignedNostrEvent event = signal.signForRoom(unsigned).await();
            assertTrue(event.verify());
            signaling.onSubEvent(event, false);
            assertEquals(0, ((AtomicInteger) get("pendingSignals")).get());
        }

        Object get(String name) throws Exception {
            Field field = NostrRTCSignaling.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(signaling);
        }

        Map<?, ?> watermarks() throws Exception {
            return (Map<?, ?>) get("latestPresence");
        }

        @Override
        public void close() {
            signaling.close();
            originalExecutor.close();
            pool.close();
            keys.close();
        }
    }

    private static final class InlineExecutor implements AsyncExecutor {

        @Override
        public <T> AsyncTask<T> run(Callable<T> task) {
            try {
                return AsyncTask.completed(task.call());
            } catch (Exception error) {
                return AsyncTask.failed(error);
            }
        }

        @Override
        public <T> AsyncTask<T> runLater(Callable<T> task, long delay, TimeUnit unit) {
            return AsyncTask.create((resolve, reject) -> {});
        }

        @Override
        public void close() {}
    }
}
