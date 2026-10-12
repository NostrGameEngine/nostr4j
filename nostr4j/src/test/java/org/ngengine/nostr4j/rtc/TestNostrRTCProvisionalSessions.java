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

package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

/**
 * Offline room fixtures; native transport and scheduling are captured, never launched.
 */
public class TestNostrRTCProvisionalSessions {

    @Test
    public void directedSignalsWithoutAnnouncementsAllocateNeitherSocketsNorPhysicalCapacity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (int index = 0; index < 2048; index++) {
                NostrRTCPeer peer = fixture.peer(fixture.remoteSigner, "ghost-" + index);
                fixture.offer(peer, "offer");
                fixture.route(peer);
            }
            assertTrue(fixture.room.getPeers().isEmpty());
            assertTrue(fixture.room.getSockets().isEmpty());
            assertEquals(0, fixture.manager().resources());
            assertEquals(0, fixture.platform.created.get());
            assertEquals(RTCSessionLimits.MAX_PROVISIONAL_SESSIONS_PER_IDENTITY, fixture.registry.size());
            NostrKeyPairSigner legitimateSigner = NostrKeyPairSigner.generate();
            NostrRTCPeer legitimate = fixture.peer(legitimateSigner, "legitimate");
            fixture.announce(legitimate, legitimateSigner);
            assertNotNull(fixture.room.getSocket(legitimate));
            assertEquals(1, fixture.room.getPeers().size());
        }
    }

    @Test
    public void signedDirectedOfferAndRouteReachOnlyTheProvisionalRegistry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            NostrRTCPeer peer = fixture.peer(fixture.remoteSigner, "authenticated-ghost");
            fixture.deliverSigned(new NostrRTCOfferSignal(fixture.remoteSigner, fixture.keys, peer, "signed-offer"));
            fixture.deliverSigned(
                new NostrRTCRouteSignal(fixture.remoteSigner, fixture.keys, peer, List.of(), "ws://offline.invalid")
            );
            assertEquals(1, fixture.registry.size());
            assertTrue(fixture.room.getPeers().isEmpty());
            assertEquals(0, fixture.manager().resources());
            assertEquals(0, fixture.platform.created.get());
            ProvisionalPeerSignals.Pending retained = fixture.registry.take(peer);
            assertEquals("signed-offer", retained.offer.getOfferString());
            assertEquals(1, retained.routes.size());
        }
    }

    @Test
    public void legitimateOfferBeforeAnnouncementPromotesAndReplaysExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            NostrRTCPeer peer = fixture.peer(fixture.remoteSigner, "early-offer");
            fixture.offer(peer, "retained-offer");
            assertNull(fixture.room.getSocket(peer));
            assertEquals(1, fixture.registry.size());
            fixture.announce(peer, fixture.remoteSigner);
            NostrRTCSocket promoted = fixture.room.getSocket(peer);
            assertNotNull(promoted);
            assertEquals(0, fixture.registry.size());
            assertEquals(1, fixture.platform.created.get());
            assertEquals("retained-offer", fixture.platform.transport.receivedOffer.get());
            fixture.announce(peer, fixture.remoteSigner);
            assertSame(promoted, fixture.room.getSocket(peer));
            assertEquals(1, fixture.platform.created.get());
        }
    }

    @Test
    public void expiredOfferIsNotReplayedButItsNewAnnouncementStillCreatesANormalEndpoint() throws Exception {
        try (Fixture fixture = new Fixture()) {
            NostrRTCPeer peer = fixture.peer(fixture.remoteSigner, "expired-offer");
            fixture.offer(peer, "old-offer");
            fixture.clock.set(RTCSessionLimits.PROVISIONAL_LIFETIME_MILLIS);
            fixture.announce(peer, fixture.remoteSigner);
            assertNotNull(fixture.room.getSocket(peer));
            assertEquals(0, fixture.registry.size());
            assertEquals(0, fixture.platform.created.get());
            fixture.offer(peer, "new-offer");
            assertEquals("new-offer", fixture.platform.transport.receivedOffer.get());
        }
    }

    @Test
    public void perIdentityAnnouncementAdmissionNeverEvictsLiveSessionsAndExpiryReopensIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<NostrRTCPeer> peers = new ArrayList<>();
            List<NostrRTCSocket> original = new ArrayList<>();
            for (int index = 0; index < RTCSessionLimits.MAX_ANNOUNCED_SESSIONS_PER_IDENTITY; index++) {
                NostrRTCPeer peer = fixture.peer(fixture.remoteSigner, "live-" + index);
                fixture.announce(peer, fixture.remoteSigner);
                peers.add(peer);
                original.add(fixture.room.getSocket(peer));
            }
            NostrRTCPeer extra = fixture.peer(fixture.remoteSigner, "extra");
            fixture.announce(extra, fixture.remoteSigner);
            assertNull(fixture.room.getSocket(extra));
            assertEquals(RTCSessionLimits.MAX_ANNOUNCED_SESSIONS_PER_IDENTITY, fixture.room.getPeers().size());
            for (int index = 0; index < peers.size(); index++) assertSame(
                original.get(index),
                fixture.room.getSocket(peers.get(index))
            );
            fixture.removeAnnouncement(peers.get(0));
            fixture.announce(extra, fixture.remoteSigner);
            assertNotNull(fixture.room.getSocket(extra));
            assertEquals(RTCSessionLimits.MAX_ANNOUNCED_SESSIONS_PER_IDENTITY, fixture.room.getPeers().size());
        }
    }

    @Test
    public void distinctFarIdentitiesRemainLogicalBeyondBothPhysicalBudgets() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.room.setDiscoveryPriority(peer -> 0f);
            for (int index = 0; index < 80; index++) {
                NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
                fixture.announce(fixture.peer(signer, "far-" + index), signer);
            }
            assertEquals(80, fixture.room.getPeers().size());
            assertEquals(80, fixture.room.getSockets().size());
            assertEquals(0, fixture.manager().resources());
            assertEquals(0, fixture.platform.created.get());
        }
    }

    @Test
    public void discoveryOnlyMaintenanceExpiresProvisionalSignalsWithoutStartingPhysicalLinks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.offer(fixture.peer(fixture.remoteSigner, "quiet"), "offer");
            fixture.room.discover();
            assertTrue((Boolean) get(fixture.room, "maintenanceStarted"));
            Executor executor = (Executor) get(fixture.room, "executor");
            assertEquals(1, executor.scheduled.size());
            fixture.clock.set(RTCSessionLimits.PROVISIONAL_LIFETIME_MILLIS);
            executor.scheduled.remove(0).call();
            assertEquals(0, fixture.registry.size());
            assertEquals(0, fixture.manager().resources());
            assertEquals(0, fixture.platform.created.get());
        }
    }

    @Test(timeout = 5000L)
    public void closeDuringPromotionCannotReplayOrResurrectThePendingOffer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            NostrRTCPeer peer = fixture.peer(fixture.remoteSigner, "promotion-race");
            fixture.offer(peer, "offer");
            CountDownLatch published = new CountDownLatch(1), release = new CountDownLatch(1);
            fixture.room.addPeerSocketAvailableListener((remote, socket) -> {
                published.countDown();
                try {
                    assertTrue(release.await(2L, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread promotion = new Thread(
                () -> {
                    try {
                        fixture.announce(peer, fixture.remoteSigner);
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                },
                "provisional-promotion-barrier"
            );
            promotion.setDaemon(true);
            promotion.start();
            try {
                assertTrue(published.await(2L, TimeUnit.SECONDS));
                fixture.room.close();
                release.countDown();
                promotion.join(1000L);
                assertFalse(promotion.isAlive());
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals(0, fixture.registry.size());
                assertTrue(fixture.room.getPeers().isEmpty());
                assertEquals(0, fixture.platform.created.get());
            } finally {
                release.countDown();
                promotion.join(1000L);
            }
        }
    }

    @Test
    public void disconnectAndBanDiscardProvisionalSignalsWithoutCreatingEndpoints() throws Exception {
        try (Fixture fixture = new Fixture()) {
            NostrRTCPeer first = fixture.peer(fixture.remoteSigner, "first");
            NostrRTCPeer second = fixture.peer(fixture.remoteSigner, "second");
            fixture.offer(first, "offer");
            fixture.offer(second, "offer");
            fixture.room.disconnect(first);
            assertEquals(1, fixture.registry.size());
            fixture.room.ban(second.getPubkey());
            assertEquals(0, fixture.registry.size());
            fixture.offer(second, "offer");
            assertEquals(0, fixture.registry.size());
            assertTrue(fixture.room.getPeers().isEmpty());
        }
    }

    @Test
    public void configuredAnnouncementQuotaAdmitsMoreThanEightSessionsWithoutAllocatingNativeLinks() throws Exception {
        RTCSessionLimits limits = new RTCSessionLimits(128, 4, 12, 32, java.time.Duration.ofSeconds(30));
        try (Fixture fixture = new Fixture(limits)) {
            fixture.room.setDiscoveryPriority(peer -> 0f);
            for (int index = 0; index < 13; index++) {
                NostrRTCPeer peer = fixture.peer(fixture.remoteSigner, "configured-" + index);
                fixture.announce(peer, fixture.remoteSigner);
                if (index < 12) assertNotNull(fixture.room.getSocket(peer)); else assertNull(fixture.room.getSocket(peer));
            }
            assertEquals(12, fixture.room.getSockets().size());
            assertEquals(0, fixture.platform.created.get());
        }
    }

    private static final class Fixture implements AutoCloseable {

        final NGEPlatform previous;
        final Platform platform = new Platform();
        final NostrKeyPair keys;
        final NostrPool pool;
        final NostrRTCRoom room;
        final NostrKeyPairSigner remoteSigner = NostrKeyPairSigner.generate();
        final AtomicLong clock = new AtomicLong();
        final ProvisionalPeerSignals registry;

        Fixture() throws Exception {
            this(RTCSessionLimits.getDefault());
        }

        Fixture(RTCSessionLimits limits) throws Exception {
            registry = new ProvisionalPeerSignals(clock::get, limits);
            Field global = NGEPlatform.class.getDeclaredField("platform");
            global.setAccessible(true);
            previous = (NGEPlatform) global.get(null);
            global.set(null, platform);
            keys = new NostrKeyPair();
            pool = new NostrPool();
            RTCSettings settings = RTCSettings
                .getDefault("provisional-app", "provisional-protocol")
                .withStunServers(List.of())
                .withSignalingRelays(List.of())
                .withSessionLimits(limits);
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(settings, NostrKeyPairSigner.generate(), "local", keys, null);
            room = new NostrRTCRoom(settings, local, keys, pool, null);
            Field registryField = NostrRTCRoom.class.getDeclaredField("provisionalSignals");
            registryField.setAccessible(true);
            registryField.set(room, registry);
        }

        NostrRTCPeer peer(NostrKeyPairSigner signer, String session) {
            return TestProvisionalPeerSignals.peer(signer, keys, session);
        }

        void offer(NostrRTCPeer peer, String sdp) throws Exception {
            invoke(room, "onReceiveOffer", NostrRTCOfferSignal.class, new NostrRTCOfferSignal(remoteSigner, keys, peer, sdp));
        }

        void route(NostrRTCPeer peer) throws Exception {
            invoke(
                room,
                "onReceiveCandidates",
                NostrRTCRouteSignal.class,
                new NostrRTCRouteSignal(remoteSigner, keys, peer, List.of(), "ws://offline.invalid")
            );
        }

        void announce(NostrRTCPeer peer, NostrKeyPairSigner signer) throws Exception {
            invoke(
                room,
                "onAddAnnounce",
                NostrRTCConnectSignal.class,
                new NostrRTCConnectSignal(signer, keys, peer, Instant.now().plusSeconds(60L), "")
            );
        }

        void removeAnnouncement(NostrRTCPeer peer) throws Exception {
            Method method =
                NostrRTCRoom.class.getDeclaredMethod(
                        "onRemoveAnnounce",
                        NostrRTCConnectSignal.class,
                        NostrRTCSignaling.Listener.RemoveReason.class
                    );
            method.setAccessible(true);
            method.invoke(
                room,
                new NostrRTCConnectSignal(remoteSigner, keys, peer, Instant.now().plusSeconds(60L), ""),
                NostrRTCSignaling.Listener.RemoveReason.EXPIRED
            );
        }

        void deliverSigned(org.ngengine.nostr4j.rtc.signal.NostrRTCSignal signal) throws Exception {
            org.ngengine.nostr4j.event.SignedNostrEvent event = signal.toEvent(room.getLocalPeerInfo().getPubkey()).await();
            assertTrue(event.verify());
            NostrRTCSignaling signaling = (NostrRTCSignaling) get(room, "signaling");
            Method method =
                NostrRTCSignaling.class.getDeclaredMethod(
                        "onSubEvent",
                        org.ngengine.nostr4j.event.SignedNostrEvent.class,
                        boolean.class
                    );
            method.setAccessible(true);
            method.invoke(signaling, event, false);
            assertEquals(0, ((AtomicInteger) get(signaling, "pendingSignals")).get());
        }

        PhysicalConnectionManager manager() throws Exception {
            return (PhysicalConnectionManager) get(room, "physicalConnections");
        }

        @Override
        public void close() throws Exception {
            try {
                room.close();
                pool.close();
                keys.close();
            } finally {
                Field global = NGEPlatform.class.getDeclaredField("platform");
                global.setAccessible(true);
                global.set(null, previous);
            }
        }
    }

    private static final class Executor implements AsyncExecutor {

        final List<Callable<?>> scheduled = new ArrayList<>();

        @Override
        public <T> AsyncTask<T> runLater(Callable<T> task, long delay, TimeUnit unit) {
            scheduled.add(task);
            return AsyncTask.create((resolve, reject) -> {});
        }

        @Override
        public <T> AsyncTask<T> run(Callable<T> task) {
            try {
                return AsyncTask.completed(task.call());
            } catch (Exception error) {
                return AsyncTask.failed(error);
            }
        }

        @Override
        public void close() {}
    }

    private static final class Platform extends JVMAsyncPlatform {

        final AtomicInteger created = new AtomicInteger();
        final Transport transport = new Transport();

        @Override
        public AsyncExecutor newAsyncExecutor(Object key) {
            return new Executor();
        }

        @Override
        public RTCTransport newRTCTransport(Duration timeout, String session, Collection<String> stun) {
            created.incrementAndGet();
            return transport;
        }
    }

    private static final class Transport implements RTCTransport {

        final AtomicReference<String> receivedOffer = new AtomicReference<>();

        @Override
        public void start(Duration timeout, AsyncExecutor executor, String id, Collection<String> stun) {}

        @Override
        public void addListener(RTCTransportListener listener) {}

        @Override
        public void removeListener(RTCTransportListener listener) {}

        @Override
        public String getName() {
            return "captured-offline-provisional-transport";
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public AsyncTask<String> listen() {
            return AsyncTask.failed(new IllegalStateException("Controlled offline provider"));
        }

        @Override
        public AsyncTask<String> connect(String sdp) {
            receivedOffer.set(sdp);
            return AsyncTask.create((resolve, reject) -> {});
        }

        @Override
        public RTCDataChannel getDataChannel(String name) {
            return null;
        }

        @Override
        public void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates) {}

        @Override
        public AsyncTask<RTCDataChannel> createDataChannel(
            String name,
            String protocol,
            boolean ordered,
            boolean reliable,
            int retransmits,
            Duration lifetime
        ) {
            return AsyncTask.failed(new IllegalStateException("Controlled offline provider"));
        }

        @Override
        public void close() {}
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Object invoke(Object owner, String name, Class<?> type, Object argument) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(owner, argument);
    }
}
