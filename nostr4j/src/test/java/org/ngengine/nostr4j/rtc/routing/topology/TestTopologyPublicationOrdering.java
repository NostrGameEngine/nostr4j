/**
 * BSD 3-Clause License. Copyright (c) 2026, Riccardo Balbo.
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

/**
 * Real encrypted/signed topology events; in-process relay only, with controlled crypto completion.
 */
public class TestTopologyPublicationOrdering {

    @Test(timeout = 12000)
    public void olderCryptoCannotPublishAfterNewerTopology() throws Exception {
        try (Fixture f = new Fixture()) {
            AsyncTask<SignedNostrEvent> first = f.control.publishNow(Instant.now());
            AsyncTask<SignedNostrEvent> second = f.control.publishNow(Instant.now());
            await(() -> f.signer.held.size() >= 1);
            // The original implementation starts both operations. Deliberately complete newer first.
            if (f.signer.held.size() >= 2) {
                f.signer.release(1);
                await(() -> f.pool.events.size() == 1);
                f.signer.release(0);
            } else {
                f.signer.release(0);
                await(() -> f.signer.held.size() == 2);
                f.signer.release(1);
            }
            await(() -> first.isDone() && second.isDone() && f.pool.events.size() == 2);
            System.out.println("TOPOLOGY_ORDER events=" + f.pool.revisions() + " olderRejected=" + f.pool.rejected.get());
            assertEquals("older signed topology must never follow newer publication", 0, f.pool.rejected.get());
            assertTrue(f.pool.events.get(1).getCreatedAt().isAfter(f.pool.events.get(0).getCreatedAt()));
        }
    }

    @Test(timeout = 10000)
    public void closeFencesAnAlreadyRunningRealSignature() throws Exception {
        Fixture f = new Fixture();
        try {
            AsyncTask<SignedNostrEvent> request = f.control.publishNow(Instant.now());
            await(() -> f.signer.held.size() == 1);
            f.control.close();
            f.signer.release(0);
            assertTrue("actual delegated signing must finish", f.signer.signed.await(3, TimeUnit.SECONDS));
            // Observe actual publication, not the state of a canceled wrapper future.
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
            while (f.pool.events.isEmpty() && System.nanoTime() < until) Thread.sleep(2L);
            System.out.println("TOPOLOGY_CLOSE realSigned=true published=" + f.pool.events.size());
            assertTrue("closed topology owner must not publish late crypto", f.pool.events.isEmpty());
            await(request::isDone);
            assertTrue(request.isFailed());
            assertTrue(f.control.getSnapshots(Instant.now()).isEmpty());
        } finally {
            f.close();
        }
    }

    @Test(timeout = 10000)
    public void heldCryptoCoalescesAnUnboundedPublicationBurst() throws Exception {
        try (Fixture f = new Fixture()) {
            List<AsyncTask<SignedNostrEvent>> requests = new ArrayList<AsyncTask<SignedNostrEvent>>();
            for (int i = 0; i < 32; i++) requests.add(f.control.publishNow(Instant.now()));
            System.out.println("TOPOLOGY_BURST requested=32 activeCrypto=" + f.signer.held.size());
            assertEquals("only one real signing/encryption lifecycle may be active", 1, f.signer.held.size());
            for (int i = 2; i < 32; i++) assertSame(
                "one latest pending publication, not thirty queued snapshots",
                requests.get(1),
                requests.get(i)
            );
        }
    }

    @Test(timeout = 12000)
    public void latestNeighborsWaitForEligibleWallSecondWithoutFutureAccumulation() throws Exception {
        AtomicReference<Instant> clock = new AtomicReference<Instant>(
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        );
        try (Fixture f = new Fixture(clock::get)) {
            Instant original = clock.get();
            AsyncTask<SignedNostrEvent> first = f.control.publishNow(original);
            f.signer.release(0);
            await(first::isDone);
            TopologyNeighbor earlier = f.neighbor("earlier");
            TopologyNeighbor latest = f.neighbor("latest");
            f.control.requestPublish(List.of(earlier));
            AsyncTask<SignedNostrEvent> pending = f.control.publishNow(original.plusSeconds(1000));
            f.control.requestPublish(List.of(latest));
            assertSame(pending, f.control.publishNow(original));
            clock.set(original.minusSeconds(10));
            assertSame(pending, f.control.publishNow(clock.get()));
            assertEquals("clock rewind/future requested time cannot start another encryption", 1, f.signer.held.size());
            clock.set(original.plusSeconds(1));
            assertSame(pending, f.control.publishNow(clock.get()));
            await(() -> f.signer.held.size() == 2);
            f.signer.release(1);
            await(pending::isDone);
            SignedNostrEvent second = pending.await();
            assertEquals(original.plusSeconds(1), second.getCreatedAt());
            assertEquals("2", second.getFirstTagFirstValue("revision"));
            TopologySnapshot decoded = new TopologyEventCodec()
                .decode(second, f.scope, f.presence(), f.room, clock.get())
                .await();
            assertEquals(List.of(latest), decoded.getNeighbors());
            assertEquals(2, f.pool.events.size());
        }
    }

    @Test(timeout = 12000)
    public void healthyRelayAllowsLatestWhileAnotherRelayRemainsUnresolved() throws Exception {
        AtomicReference<Instant> clock = new AtomicReference<Instant>(Instant.now());
        try (Fixture f = new Fixture(clock::get)) {
            Pending<NostrMessageAck> slow = new Pending<NostrMessageAck>();
            f.pool.result = event -> AsyncTask.completed(List.of(AsyncTask.completed(ack(event, true)), slow.task));
            AsyncTask<SignedNostrEvent> first = f.control.publishNow(clock.get());
            f.signer.release(0);
            await(first::isDone);
            assertTrue(first.isSuccess());
            assertFalse(slow.task.isDone());
            clock.set(clock.get().plusSeconds(2));
            f.pool.result = event -> AsyncTask.completed(Collections.emptyList());
            AsyncTask<SignedNostrEvent> latest = f.control.publishNow(clock.get());
            await(() -> f.signer.held.size() == 2);
            f.signer.release(1);
            await(latest::isDone);
            assertTrue("healthy pool ACK must allow progress without waiting for another relay", latest.isSuccess());
            assertFalse("no timeout or cancellation fiction for the other relay", slow.task.isDone());
            assertEquals(2, f.pool.events.size());
            assertEquals(0, f.pool.rejected.get());
            slow.success(ack(f.pool.events.get(0), false));
        }
    }

    @Test(timeout = 12000)
    public void closeRejectsActiveAndCoalescedCallersAndFencesLateRelayCompletion() throws Exception {
        AtomicReference<Instant> clock = new AtomicReference<Instant>(Instant.now());
        try (Fixture f = new Fixture(clock::get)) {
            Pending<NostrMessageAck> slow = new Pending<NostrMessageAck>();
            Pending<List<AsyncTask<NostrMessageAck>>> policy = new Pending<List<AsyncTask<NostrMessageAck>>>();
            f.pool.result = event -> policy.task;
            AsyncTask<SignedNostrEvent> first = f.control.publishNow(clock.get());
            f.signer.release(0);
            await(() -> f.pool.events.size() == 1);
            clock.set(clock.get().plusSeconds(2));
            AsyncTask<SignedNostrEvent> pending = f.control.publishNow(clock.get());
            assertSame(pending, f.control.publishNow(clock.get()));
            f.control.close();
            await(() -> first.isDone() && pending.isDone());
            assertTrue(first.isFailed());
            assertTrue(pending.isFailed());
            slow.success(ack(f.pool.events.get(0), true));
            policy.success(List.of(slow.task));
            assertTrue(slow.task.isDone());
            Thread.sleep(40L);
            assertEquals(1, f.signer.held.size());
            assertEquals(1, f.pool.events.size());
            assertTrue(f.control.getSnapshots(clock.get()).isEmpty());
        }
    }

    @Test(timeout = 12000)
    public void relayFailureReleasesOnlyTheLatestQueuedPublication() throws Exception {
        AtomicReference<Instant> clock = new AtomicReference<Instant>(Instant.now());
        try (Fixture f = new Fixture(clock::get)) {
            Pending<List<AsyncTask<NostrMessageAck>>> rejection = new Pending<List<AsyncTask<NostrMessageAck>>>();
            f.pool.result = event -> rejection.task;
            AsyncTask<SignedNostrEvent> first = f.control.publishNow(clock.get());
            f.signer.release(0);
            await(() -> f.pool.events.size() == 1);
            clock.set(clock.get().plusSeconds(2));
            AsyncTask<SignedNostrEvent> pending = f.control.publishNow(clock.get());
            f.pool.result = event -> AsyncTask.completed(Collections.emptyList());
            rejection.failure(new IllegalStateException("both relays rejected"));
            await(() -> f.signer.held.size() == 2);
            f.signer.release(1);
            await(pending::isDone);
            assertTrue(first.isFailed());
            assertTrue(pending.isSuccess());
            assertEquals(2, f.pool.events.size());
        }
    }

    @Test(timeout = 12000)
    public void encodingFailureDoesNotRetainTheActiveSlotOrPublishAnUnsignedSnapshot() throws Exception {
        AtomicReference<Instant> clock = new AtomicReference<Instant>(Instant.now());
        try (Fixture f = new Fixture(clock::get)) {
            AsyncTask<SignedNostrEvent> first = f.control.publishNow(clock.get());
            clock.set(clock.get().plusSeconds(2));
            AsyncTask<SignedNostrEvent> pending = f.control.publishNow(clock.get());
            f.signer.pending.get(0).failure(new IllegalStateException("owned signer refused"));
            await(() -> f.signer.held.size() == 2);
            f.signer.release(1);
            await(pending::isDone);
            assertTrue(first.isFailed());
            assertTrue(pending.isSuccess());
            assertEquals(1, f.pool.events.size());
            assertEquals("2", f.pool.events.get(0).getFirstTagFirstValue("revision"));
        }
    }

    @Test(timeout = 12000)
    public void reentrantCloseDuringPoolHandoffCannotRepopulateStoreOrNotifyListener() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger notifications = new AtomicInteger();
            f.control.setListener(notifications::incrementAndGet);
            f.pool.result =
                event -> {
                    f.control.close();
                    return AsyncTask.completed(Collections.emptyList());
                };
            AsyncTask<SignedNostrEvent> first = f.control.publishNow(Instant.now());
            f.signer.release(0);
            await(first::isDone);
            assertTrue(first.isFailed());
            assertEquals(1, f.pool.events.size());
            assertTrue(f.control.getSnapshots(Instant.now()).isEmpty());
            assertEquals(0, notifications.get());
            AsyncTask<SignedNostrEvent> rejected = f.control.publishNow(Instant.now());
            await(rejected::isDone);
            assertTrue(rejected.isFailed());
        }
    }

    static NostrMessageAck ack(SignedNostrEvent event, boolean success) {
        try {
            java.lang.reflect.Constructor<NostrMessageAck> constructor =
                NostrMessageAck.class.getDeclaredConstructor(
                        NostrRelay.class,
                        String.class,
                        Instant.class,
                        java.util.function.BiConsumer.class,
                        java.util.function.BiConsumer.class
                    );
            constructor.setAccessible(true);
            NostrMessageAck ack = constructor.newInstance(null, event.getId(), Instant.now(), null, null);
            if (success) ack.callSuccessCallback("stored"); else ack.callFailureCallback("relay refused");
            return ack;
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2L);
        assertTrue("bounded actual asynchronous boundary", condition.getAsBoolean());
    }

    static final class Pending<T> {

        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicBoolean terminal = new AtomicBoolean();
        Consumer<T> resolve;
        Consumer<Throwable> reject;
        final AsyncTask<T> task = AsyncTask.create((yes, no) -> {
            resolve = yes;
            reject = no;
            ready.countDown();
        });

        void success(T value) throws Exception {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            if (terminal.compareAndSet(false, true)) resolve.accept(value);
        }

        void failure(Throwable error) throws Exception {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            if (terminal.compareAndSet(false, true)) reject.accept(error);
        }
    }

    static final class HoldingSigner implements NostrSigner {

        final NostrKeyPairSigner delegate = new NostrKeyPairSigner(new NostrKeyPair());
        final List<Runnable> held = new CopyOnWriteArrayList<Runnable>();
        final List<Pending<String>> pending = new CopyOnWriteArrayList<Pending<String>>();
        final CountDownLatch signed = new CountDownLatch(1);

        @Override
        public AsyncTask<String> encrypt(String text, NostrPublicKey key, EncryptAlgo algo) {
            Pending<String> pending = new Pending<String>();
            this.pending.add(pending);
            held.add(() ->
                delegate
                    .encrypt(text, key, algo)
                    .then(value -> {
                        pending.success(value);
                        return null;
                    })
                    .catchException(error -> {
                        try {
                            pending.failure(error);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
            );
            return pending.task;
        }

        void release(int index) {
            held.get(index).run();
        }

        @Override
        public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
            return delegate
                .sign(event)
                .then(value -> {
                    signed.countDown();
                    return value;
                });
        }

        @Override
        public AsyncTask<String> decrypt(String text, NostrPublicKey key, EncryptAlgo algo) {
            return delegate.decrypt(text, key, algo);
        }

        @Override
        public AsyncTask<NostrPublicKey> getPublicKey() {
            return delegate.getPublicKey();
        }

        @Override
        public AsyncTask<NostrSigner> close() {
            for (Pending<String> item : pending) try {
                item.failure(new java.util.concurrent.CancellationException("fixture signer closed"));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            return delegate.close();
        }
    }

    static final class RelayPool extends NostrPool {

        final List<SignedNostrEvent> events = new CopyOnWriteArrayList<SignedNostrEvent>();
        final AtomicInteger rejected = new AtomicInteger();
        final java.util.Map<String, SignedNostrEvent> latestByAddress = new java.util.HashMap<>();
        volatile Function<SignedNostrEvent, AsyncTask<List<AsyncTask<NostrMessageAck>>>> result = event ->
            AsyncTask.completed(Collections.emptyList());

        @Override
        public synchronized AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent event) {
            String address = event.getFirstTagFirstValue("d");
            boolean compact = address.matches("dc-topology:[0-9a-f]{64}");
            if (compact) events.add(event);
            SignedNostrEvent latest = latestByAddress.get(address);
            if (
                latest != null &&
                (
                    event.getCreatedAt().isBefore(latest.getCreatedAt()) ||
                    (event.getCreatedAt().equals(latest.getCreatedAt()) && event.getId().compareTo(latest.getId()) > 0)
                )
            ) {
                rejected.incrementAndGet();
                return AsyncTask.failed(new IllegalStateException("replaced: have newer event"));
            }
            latestByAddress.put(address, event);
            return compact ? result.apply(event) : AsyncTask.completed(Collections.emptyList());
        }

        List<String> revisions() {
            List<String> values = new ArrayList<String>();
            for (SignedNostrEvent event : events) values.add(event.getFirstTagFirstValue("revision"));
            return values;
        }
    }

    static final class Fixture implements AutoCloseable {

        final HoldingSigner signer = new HoldingSigner();
        final RelayPool pool = new RelayPool();
        final TopologyControlPlane control;
        final NostrKeyPair room = new NostrKeyPair();
        final NostrRTCLocalPeer local;
        final RoutingScope scope;

        Fixture() {
            this(null);
        }

        Fixture(Supplier<Instant> clock) {
            local =
                new NostrRTCLocalPeer(
                    RTCSettings.getDefault("order-app", "order-proto").withStunServers(Collections.emptyList()),
                    signer,
                    "fixed-native-session",
                    room,
                    null
                );
            scope = new RoutingScope(room.getPublicKey(), local.getProtocolId(), local.getApplicationId());
            control =
                clock == null
                    ? new TopologyControlPlane(
                        scope,
                        local,
                        room,
                        new NostrKeyPair(),
                        pool,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5)
                    )
                    : new TopologyControlPlane(
                        scope,
                        local,
                        room,
                        new NostrKeyPair(),
                        pool,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5),
                        clock
                    );
        }

        NostrRTCPeer presence() {
            return new NostrRTCPeer(
                local.getPubkey(),
                local.getApplicationId(),
                local.getProtocolId(),
                local.getSessionId(),
                room.getPublicKey(),
                null
            );
        }

        TopologyNeighbor neighbor(String session) {
            NostrPublicKey key = new NostrKeyPair().getPublicKey();
            NodeId node = NodeId.derive(scope, key, session);
            return new TopologyNeighbor(
                node,
                key,
                session,
                EdgeId.derive(scope, NodeId.derive(scope, local.getPubkey(), local.getSessionId()), node),
                TopologyTransport.RTC
            );
        }

        @Override
        public void close() {
            control.close();
            pool.close();
            signer.close();
        }
    }
}
