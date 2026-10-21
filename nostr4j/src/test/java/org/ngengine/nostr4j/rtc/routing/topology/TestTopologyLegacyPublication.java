/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

public class TestTopologyLegacyPublication {

    @Test(timeout = 12000)
    public void simultaneousPublicationsCanDeliverToOneAnotherBeforeAcknowledgement() throws Exception {
        ExecutorService delivery = Executors.newFixedThreadPool(2);
        CyclicBarrier publishing = new CyclicBarrier(2);
        CountDownLatch leftReceived = new CountDownLatch(1), rightReceived = new CountDownLatch(1);
        CrossDeliveryPool leftPool = new CrossDeliveryPool(delivery, publishing, rightReceived);
        CrossDeliveryPool rightPool = new CrossDeliveryPool(delivery, publishing, leftReceived);
        try (
            NostrKeyPair room = new NostrKeyPair();
            NostrKeyPair leftRouting = new NostrKeyPair();
            NostrKeyPair rightRouting = new NostrKeyPair()
        ) {
            NostrKeyPairSigner leftSigner = NostrKeyPairSigner.generate(), rightSigner = NostrKeyPairSigner.generate();
            RTCSettings settings = RTCSettings.getDefault("app", "proto").withStunServers(List.of());
            NostrRTCLocalPeer left = new NostrRTCLocalPeer(settings, leftSigner, "left", room, null);
            NostrRTCLocalPeer right = new NostrRTCLocalPeer(settings, rightSigner, "right", room, null);
            RoutingScope scope = new RoutingScope(room.getPublicKey(), "proto", "app");
            TopologyControlPlane a = new TopologyControlPlane(
                scope,
                left,
                room,
                leftRouting,
                leftPool,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5)
            );
            TopologyControlPlane b = new TopologyControlPlane(
                scope,
                right,
                room,
                rightRouting,
                rightPool,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5)
            );
            try {
                Instant now = Instant.now();
                leftPool.recipient = b;
                rightPool.recipient = a;
                a.setListener(leftReceived::countDown);
                b.setListener(rightReceived::countDown);
                a.updatePresences(List.of(new NostrRTCConnectSignal(rightSigner, room, right, now.plusSeconds(60), "")), now);
                b.updatePresences(List.of(new NostrRTCConnectSignal(leftSigner, room, left, now.plusSeconds(60), "")), now);
                AsyncTask<SignedNostrEvent> first = a.publishNow(now), second = b.publishNow(now);
                assertNotNull(first.await());
                assertNotNull(second.await());
                assertEquals(2, a.getSnapshots(now).size());
                assertEquals(2, b.getSnapshots(now).size());
            } finally {
                a.close();
                b.close();
                leftSigner.close().await();
                rightSigner.close().await();
            }
        } finally {
            delivery.shutdownNow();
            leftPool.close();
            rightPool.close();
        }
    }

    @Test(timeout = 12000)
    public void shortAddressPublishesTwoSignedCopiesOfOnePrivateSnapshot() throws Exception {
        try (Fixture f = new Fixture("app")) {
            SignedNostrEvent compact = f.publish();
            await(() -> f.pool.legacy.size() == 1);
            SignedNostrEvent legacy = f.pool.legacy.get(0);
            assertTrue(compact.verify());
            assertTrue(legacy.verify());
            assertNotEquals(compact.getId(), legacy.getId());
            assertEquals(76, compact.getFirstTagFirstValue("d").length());
            assertEquals(f.scope.legacyTopologyAddress("session"), legacy.getFirstTagFirstValue("d"));
            assertEquals(compact.getContent(), legacy.getContent());
            assertEquals(compact.getFirstTag("roomproof").getAll(), legacy.getFirstTag("roomproof").getAll());
            assertEquals(compact.getCreatedAt(), legacy.getCreatedAt());
            assertEquals(compact.getExpiration(), legacy.getExpiration());
            assertEquals(1, compact.getTag("d").size());
            assertEquals(1, legacy.getTag("d").size());
            assertEquals("d", compact.getTagRows().get(0).get(0));
            assertEquals("d", legacy.getTagRows().get(0).get(0));
            TopologyEventCodec codec = new TopologyEventCodec();
            TopologySnapshot modern = codec.decode(compact, f.scope, f.presence(), f.room, f.clock.get()).await();
            TopologySnapshot old = codec.decode(legacy, f.scope, f.presence(), f.room, f.clock.get()).await();
            assertEquals(modern.getNodeId(), old.getNodeId());
            assertEquals(modern.getRevision(), old.getRevision());
            assertEquals(modern.getRoutingPublicKey(), old.getRoutingPublicKey());
            assertEquals(modern.getNeighbors(), old.getNeighbors());
            for (List<TopologySnapshot> order : List.of(List.of(modern, old), List.of(old, modern))) {
                TopologySnapshotStore store = new TopologySnapshotStore();
                assertTrue(store.accept(order.get(0), f.clock.get()));
                assertFalse("An alias cannot create another logical update", store.accept(order.get(1), f.clock.get()));
                assertEquals(1, store.size());
            }
        }
    }

    @Test(timeout = 12000)
    public void legacyPublicationUsesTheInclusive255Utf8ByteBoundary() throws Exception {
        for (String app : List.of("a".repeat(164), "é".repeat(82), "a".repeat(165), "é".repeat(82) + "a")) {
            try (Fixture f = new Fixture(app)) {
                int bytes = f.scope.legacyTopologyAddress("session").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                assertTrue(bytes == 255 || bytes == 256);
                SignedNostrEvent compact = f.publish();
                assertEquals(76, compact.getFirstTagFirstValue("d").length());
                if (bytes == 255) {
                    await(() -> f.pool.legacy.size() == 1);
                } else {
                    assertTrue("Oversized legacy addresses must never be scheduled", f.pool.legacy.isEmpty());
                }
                assertEquals(1, f.pool.compact.size());
            }
        }
    }

    @Test(timeout = 12000)
    public void legacyFailuresDoNotFailCanonicalOrLaterRevisions() throws Exception {
        for (boolean synchronous : List.of(false, true)) {
            try (Fixture f = new Fixture("app")) {
                f.pool.legacyResult =
                    event -> {
                        if (synchronous) throw new IllegalStateException("legacy relay rejected synchronously");
                        return AsyncTask.failed(new IllegalStateException("legacy relay rejected"));
                    };
                assertNotNull(f.publish());
                await(() -> f.pool.legacy.size() == 1);
                f.tick();
                SignedNostrEvent next = f.publish();
                assertEquals("2", next.getFirstTagFirstValue("revision"));
                await(() -> f.pool.legacy.size() == 2);
                assertEquals(2, f.pool.compact.size());
            }
        }
    }

    @Test(timeout = 15000)
    public void unresolvedLegacyAcknowledgementCoalescesWithoutHoldingCanonicalUpdates() throws Exception {
        try (Fixture f = new Fixture("app")) {
            Pending<List<AsyncTask<NostrMessageAck>>> held = new Pending<>();
            f.pool.legacyResult = event -> held.task;
            f.publish();
            await(() -> f.pool.legacy.size() == 1);
            SignedNostrEvent latest = null;
            for (int revision = 2; revision <= 16; revision++) {
                f.tick();
                latest = f.publish();
            }
            assertEquals(16, f.pool.compact.size());
            assertEquals("One active legacy operation must bound all retained work", 1, f.pool.legacy.size());
            f.pool.legacyResult = event -> AsyncTask.completed(Collections.emptyList());
            held.success(Collections.emptyList());
            await(() -> f.pool.legacy.size() == 2);
            SignedNostrEvent legacy = f.pool.legacy.get(1);
            assertEquals("16", legacy.getFirstTagFirstValue("revision"));
            assertEquals(latest.getContent(), legacy.getContent());
            assertTrue(legacy.getCreatedAt().isAfter(f.pool.legacy.get(0).getCreatedAt()));
        }
    }

    @Test(timeout = 12000)
    public void blockingLegacyHandoffDoesNotOwnTheCanonicalHandoffMonitor() throws Exception {
        try (Fixture f = new Fixture("app")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            f.pool.legacyResult =
                event -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(
                            "legacy handoff stayed blocked"
                        );
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    return AsyncTask.completed(Collections.emptyList());
                };
            try {
                f.publish();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                f.tick();
                assertEquals("2", f.publish().getFirstTagFirstValue("revision"));
                assertEquals(2, f.pool.compact.size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 12000)
    public void unresolvedLegacySignatureDoesNotDelayCanonicalAndCloseFencesItsCompletion() throws Exception {
        try (Fixture f = new Fixture("app")) {
            f.signer.holdLegacy = true;
            f.publish();
            await(() -> f.signer.pending.size() == 1);
            f.tick();
            assertEquals("2", f.publish().getFirstTagFirstValue("revision"));
            assertEquals(2, f.pool.compact.size());
            assertEquals(1, f.signer.pending.size());
            f.control.close();
            f.signer.release(0);
            await(() -> f.signer.pending.get(0).task.isDone());
            assertTrue(f.pool.legacy.isEmpty());
            assertTrue(f.control.getSnapshots(f.clock.get()).isEmpty());
        }
    }

    @Test(timeout = 12000)
    public void expiredLegacySignatureIsSkippedBeforeTheLatestPendingCopy() throws Exception {
        try (Fixture f = new Fixture("app")) {
            f.signer.holdLegacy = true;
            f.publish();
            await(() -> f.signer.pending.size() == 1);
            f.clock.updateAndGet(now -> now.plusSeconds(61));
            f.publish();
            f.signer.release(0);
            await(() -> f.signer.pending.size() == 2);
            assertTrue("Expired signed legacy work must not reach the pool", f.pool.legacy.isEmpty());
            f.signer.release(1);
            await(() -> f.pool.legacy.size() == 1);
            assertEquals("2", f.pool.legacy.get(0).getFirstTagFirstValue("revision"));
        }
    }

    @Test(timeout = 12000)
    public void canonicalRejectionStillAllowsTheIndependentLegacyPublication() throws Exception {
        try (Fixture f = new Fixture("app")) {
            f.pool.compactResult = event -> AsyncTask.failed(new IllegalStateException("compact relay rejected"));
            AsyncTask<SignedNostrEvent> result = f.control.publishNow(f.clock.get());
            await(result::isDone);
            assertTrue(result.isFailed());
            await(() -> f.pool.legacy.size() == 1);
            assertTrue(f.pool.legacy.get(0).verify());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(2L);
        assertTrue("Bounded asynchronous topology operation", condition.getAsBoolean());
    }

    private static final class Pending<T> {

        private final CountDownLatch ready = new CountDownLatch(1);
        private final AtomicBoolean done = new AtomicBoolean();
        private Consumer<T> resolve;
        private Consumer<Throwable> reject;
        private final AsyncTask<T> task = AsyncTask.create((yes, no) -> {
            resolve = yes;
            reject = no;
            ready.countDown();
        });

        void success(T value) throws Exception {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            if (done.compareAndSet(false, true)) resolve.accept(value);
        }

        void failure(Throwable error) throws Exception {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            if (done.compareAndSet(false, true)) reject.accept(error);
        }
    }

    private static final class LegacySigner implements NostrSigner {

        private final NostrKeyPairSigner delegate = NostrKeyPairSigner.generate();
        private final List<Pending<SignedNostrEvent>> pending = new CopyOnWriteArrayList<>();
        private final List<UnsignedNostrEvent> unsigned = new CopyOnWriteArrayList<>();
        private volatile boolean holdLegacy;

        @Override
        public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
            if (holdLegacy && !event.getFirstTagFirstValue("d").matches("dc-topology:[0-9a-f]{64}")) {
                Pending<SignedNostrEvent> held = new Pending<>();
                unsigned.add(event);
                pending.add(held);
                return held.task;
            }
            return delegate.sign(event);
        }

        void release(int index) throws Exception {
            pending.get(index).success(delegate.sign(unsigned.get(index)).await());
        }

        @Override
        public AsyncTask<String> encrypt(String text, NostrPublicKey peer, EncryptAlgo algo) {
            return delegate.encrypt(text, peer, algo);
        }

        @Override
        public AsyncTask<String> decrypt(String text, NostrPublicKey peer, EncryptAlgo algo) {
            return delegate.decrypt(text, peer, algo);
        }

        @Override
        public AsyncTask<NostrPublicKey> getPublicKey() {
            return delegate.getPublicKey();
        }

        @Override
        public AsyncTask<NostrSigner> close() {
            for (Pending<SignedNostrEvent> held : pending) {
                try {
                    held.failure(new java.util.concurrent.CancellationException("fixture closed"));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
            return delegate.close();
        }
    }

    private static final class CapturingPool extends NostrPool {

        private final List<SignedNostrEvent> compact = new CopyOnWriteArrayList<>();
        private final List<SignedNostrEvent> legacy = new CopyOnWriteArrayList<>();
        private volatile Function<SignedNostrEvent, AsyncTask<List<AsyncTask<NostrMessageAck>>>> compactResult = event ->
            AsyncTask.completed(Collections.emptyList());
        private volatile Function<SignedNostrEvent, AsyncTask<List<AsyncTask<NostrMessageAck>>>> legacyResult = compactResult;

        @Override
        public AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent event) {
            if (event.getFirstTagFirstValue("d").matches("dc-topology:[0-9a-f]{64}")) {
                compact.add(event);
                return compactResult.apply(event);
            }
            legacy.add(event);
            return legacyResult.apply(event);
        }
    }

    private static final class CrossDeliveryPool extends NostrPool {

        private final ExecutorService delivery;
        private final CyclicBarrier publishing;
        private final CountDownLatch received;
        private final AtomicBoolean first = new AtomicBoolean(true);
        private volatile TopologyControlPlane recipient;

        CrossDeliveryPool(ExecutorService delivery, CyclicBarrier publishing, CountDownLatch received) {
            this.delivery = delivery;
            this.publishing = publishing;
            this.received = received;
        }

        @Override
        public AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent event) {
            if (event.getFirstTagFirstValue("d").matches("dc-topology:[0-9a-f]{64}") && first.compareAndSet(true, false)) {
                try {
                    publishing.await(3, TimeUnit.SECONDS);
                    delivery.execute(() -> recipient.acceptEvent(event, Instant.now()));
                    if (!received.await(3, TimeUnit.SECONDS)) {
                        return AsyncTask.failed(
                            new IllegalStateException("Cross-delivery waited for another publication lock")
                        );
                    }
                } catch (Exception error) {
                    return AsyncTask.failed(error);
                }
            }
            return AsyncTask.completed(Collections.emptyList());
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final NostrKeyPair room = new NostrKeyPair();
        private final NostrKeyPair routing = new NostrKeyPair();
        private final LegacySigner signer = new LegacySigner();
        private final CapturingPool pool = new CapturingPool();
        private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        private final NostrRTCLocalPeer local;
        private final RoutingScope scope;
        private final TopologyControlPlane control;

        Fixture(String app) {
            local =
                new NostrRTCLocalPeer(
                    RTCSettings.getDefault(app, "proto").withStunServers(List.of()),
                    signer,
                    "session",
                    room,
                    null
                );
            scope = new RoutingScope(room.getPublicKey(), "proto", app);
            control =
                new TopologyControlPlane(
                    scope,
                    local,
                    room,
                    routing,
                    pool,
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(5),
                    clock::get
                );
        }

        SignedNostrEvent publish() throws Exception {
            return control.publishNow(clock.get()).await();
        }

        void tick() {
            clock.updateAndGet(now -> now.plusSeconds(1));
        }

        NostrRTCPeer presence() {
            return new NostrRTCPeer(
                local.getPubkey(),
                local.getApplicationId(),
                local.getProtocolId(),
                "session",
                room.getPublicKey(),
                null
            );
        }

        @Override
        public void close() throws Exception {
            control.close();
            pool.close();
            signer.close().await();
            routing.close();
            room.close();
        }
    }
}
