/**
 * BSD 3-Clause License. Copyright (c) 2026, Riccardo Balbo.
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCDataChannel;

/** Actual room sends and logical channel callbacks with a controlled native provider.
 * No relay, TURN, native RTC or wall-clock deadline advance is needed to retire a terminal owner. */
public final class TestNostrRTCRoomTerminalQueues {

    private static final String APPLICATION = "terminal-queue-app", PROTOCOL = "terminal-queue-protocol";
    private static final String CHANNEL = "terminal-owned-presence";

    @Test(timeout = 5000L)
    public void terminalLogicalCloseSettlesOriginalQueuedPromiseWithoutWaitingForDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Probe original = fixture.send(fixture.first);
            BlockingPacketQueue<?> queue = fixture.queue(fixture.first.channel);
            assertEquals(1, queue.size());
            System.out.println("TERMINAL3157_ORIGINAL_SEND_HELD queueSize=" + queue.size() + " clock=" + fixture.clock.get());
            fixture.first.channel.close();
            await(() -> original.failures.get() == 1);
            assertEquals(0, original.successes.get());
            assertTrue("Completion must be outside the room monitor", original.outsideRoom.get());
            assertNull(fixture.queue(fixture.first.channel));
            assertEquals(0, queue.size());
            assertEquals(1_000_000L, fixture.clock.get());
            fixture.first.provider.writes.get(0).succeed();
            queue.loop();
            assertEquals(1, original.failures.get());
            assertEquals(0, original.successes.get());
        }
    }

    @Test(timeout = 5000L)
    public void terminalSocketCloseCannotAffectReplacementNativeWithTheSameLabel() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Probe old = fixture.send(fixture.first);
            fixture.first.socket.close();
            await(() -> old.failures.get() == 1);
            Endpoint replacement = fixture.replacement("new-native");
            Probe current = fixture.send(replacement);
            BlockingPacketQueue<?> currentQueue = fixture.queue(replacement.channel);
            assertNotNull(currentQueue);
            assertEquals(1, currentQueue.size());
            fixture.first.provider.writes.get(0).succeed();
            ((NostrRTCChannelListener) fixture.listener).onRTCChannelClosed(fixture.first.channel);
            assertSame(currentQueue, fixture.queue(replacement.channel));
            assertEquals(0, current.failures.get());
            assertEquals(0, current.successes.get());
            replacement.provider.writes.get(0).succeed();
            await(() -> current.successes.get() == 1);
            assertEquals(0, current.failures.get());
            assertEquals(1, old.failures.get());
            assertEquals(0, old.successes.get());
            assertEquals(0, currentQueue.size());
        }
    }

    @Test(timeout = 5000L)
    public void closedLogicalOwnerCannotResurrectAQueueAndRetainedQueueCannotAcceptAnotherSend() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Probe original = fixture.send(fixture.first);
            BlockingPacketQueue<NostrRTCChannel.PreparedPacket> retained = fixture.queue(fixture.first.channel);
            fixture.first.provider.blockClose = true;
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread closer = new Thread(
                () -> {
                    try {
                        fixture.first.channel.close();
                    } catch (Throwable failure) {
                        closeFailure.set(failure);
                    }
                },
                "terminal-logical-close"
            );
            closer.start();
            try {
                assertTrue(fixture.first.provider.closeEntered.await(2L, TimeUnit.SECONDS));
                assertTrue(fixture.first.channel.isClosed());
                assertFalse("The logical channel can terminate before its socket", fixture.first.socket.isClosed());
                System.out.println("TERMINAL3157_PRODUCER_RACES_BEFORE_CLOSE_CALLBACK");
                try {
                    fixture.room.send(fixture.first.channel, ByteBuffer.wrap(new byte[] { 2 }));
                    fail("Closed logical channel must reject before creating a new queue");
                } catch (IllegalStateException expected) {}
                assertSame(retained, fixture.queue(fixture.first.channel));
                assertEquals(1, retained.size());
                assertEquals(0, original.failures.get());
            } finally {
                fixture.first.provider.closeRelease.countDown();
                closer.join(2000L);
            }
            assertFalse("Exact terminal closer returned", closer.isAlive());
            if (closeFailure.get() != null) throw new AssertionError(closeFailure.get());
            await(() -> original.failures.get() == 1);
            assertNull(fixture.queue(fixture.first.channel));
            AtomicInteger retainedRejects = new AtomicInteger();
            retained.enqueue(
                fixture.first.channel.prepareOutgoingPacket(ByteBuffer.wrap(new byte[] { 3 })),
                ignored -> fail("Retired queue resolved a new send"),
                error -> retainedRejects.incrementAndGet()
            );
            assertEquals(1, retainedRejects.get());
            assertEquals(0, retained.size());
            assertEquals(1, fixture.first.provider.writes.size());
        }
    }

    @Test(timeout = 5000L)
    public void terminalCompletionMayReenterRoomAndFinishAnotherProducerOutsideItsMonitor() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Probe original = fixture.send(fixture.first);
            Endpoint other = fixture.replacement("other-native");
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            AtomicBoolean callbackDone = new AtomicBoolean();
            original.onFailure =
                () -> {
                    try {
                        assertFalse(Thread.holdsLock(fixture.room));
                        Thread producer = new Thread(
                            () -> {
                                try {
                                    fixture.room.send(other.channel, ByteBuffer.wrap(new byte[] { 4 }));
                                } catch (Throwable failure) {
                                    callbackFailure.set(failure);
                                }
                            },
                            "terminal-queue-reentrant-producer"
                        );
                        producer.start();
                        producer.join(1000L);
                        assertFalse("Another producer must acquire the room monitor", producer.isAlive());
                    } catch (Throwable failure) {
                        callbackFailure.set(failure);
                    } finally {
                        callbackDone.set(true);
                    }
                };
            fixture.first.channel.close();
            await(callbackDone::get);
            if (callbackFailure.get() != null) throw new AssertionError(callbackFailure.get());
            await(() -> !other.provider.writes.isEmpty());
            other.provider.writes.get(0).succeed();
            assertEquals(1, original.failures.get());
        }
    }

    @Test(timeout = 8000L)
    public void physicalReplacementKeepsLogicalPromiseAndRetirementDebtUntilItsActualCloseCompletes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Probe original = fixture.send(fixture.first);
            BlockingPacketQueue<?> queue = fixture.queue(fixture.first.channel);
            fixture.first.provider.holdClose = true;
            PhysicalResourceRetirement retirement = fixture.first.socket.capturePhysicalRetirement(ignored -> {});
            retirement.start();
            await(() -> fixture.first.provider.closes.get() > 0);
            assertFalse("Held physical close remains owned", retirement.isSettled());
            assertFalse(fixture.first.channel.isClosed());
            assertFalse(fixture.first.socket.isClosed());
            assertSame(queue, fixture.queue(fixture.first.channel));
            assertEquals(0, original.failures.get());
            queue.loop();
            await(() -> field(queue, "executionQueue") == null);
            fixture.first.provider.closeCompletion.succeed();
            await(retirement::isSettled);
            assertTrue(retirement.isSuccessful());
            fixture.first.socket.setPhysicalLinkEnabled(true);
            ControlledChannel next = new ControlledChannel();
            fixture.providers.add(next);
            fixture.first.channel.setChannel(next);
            await(() -> next.writes.size() == 1);
            fixture.first.provider.writes.get(0).succeed();
            assertEquals(0, original.successes.get());
            assertEquals(0, original.failures.get());
            next.writes.get(0).succeed();
            await(() -> original.successes.get() == 1);
            assertEquals(0, original.failures.get());
            assertEquals(1_000_000L, fixture.clock.get());
        }
    }

    private static final class Probe {

        final AtomicInteger successes = new AtomicInteger(), failures = new AtomicInteger();
        final AtomicBoolean outsideRoom = new AtomicBoolean(true);
        volatile Runnable onFailure = () -> {};

        Probe(AsyncTask<Void> task, NostrRTCRoom room) {
            task
                .then(ignored -> {
                    successes.incrementAndGet();
                    return null;
                })
                .catchException(error -> {
                    outsideRoom.compareAndSet(true, !Thread.holdsLock(room));
                    failures.incrementAndGet();
                    onFailure.run();
                });
        }
    }

    private static final class Pending {

        Consumer<Void> resolve;
        Consumer<Throwable> reject;
        final CountDownLatch ready = new CountDownLatch(1);
        final AsyncTask<Void> task = AsyncTask.create((accept, fail) -> {
            resolve = accept;
            reject = fail;
            ready.countDown();
        });

        void succeed() {
            awaitReady();
            resolve.accept(null);
        }

        void fail(Throwable failure) {
            awaitReady();
            reject.accept(failure);
        }

        private void awaitReady() {
            try {
                assertTrue("Controlled provider promise registered", ready.await(2L, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }

    private static final class ControlledChannel extends RTCDataChannel {

        final List<Pending> writes = new CopyOnWriteArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        final Pending closeCompletion = new Pending();
        volatile boolean holdClose, blockClose;
        final CountDownLatch closeEntered = new CountDownLatch(1), closeRelease = new CountDownLatch(1);

        ControlledChannel() {
            super(CHANNEL, PROTOCOL, true, true, 0, null);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer data) {
            Pending pending = new Pending();
            writes.add(pending);
            return pending.task;
        }

        @Override
        public AsyncTask<Void> close() {
            closes.incrementAndGet();
            if (blockClose) {
                closeEntered.countDown();
                try {
                    assertTrue("Exact controlled terminal close released", closeRelease.await(2L, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return holdClose ? closeCompletion.task : AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Number> getMaxMessageSize() {
            return AsyncTask.completed(65536);
        }

        @Override
        public AsyncTask<Number> getAvailableAmount() {
            return AsyncTask.completed(65536);
        }

        @Override
        public AsyncTask<Number> getBufferedAmount() {
            return AsyncTask.completed(0);
        }

        @Override
        public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) {
            return AsyncTask.completed(null);
        }
    }

    private static final class Endpoint {

        final NostrRTCSocket socket;
        final NostrRTCChannel channel;
        final ControlledChannel provider;

        Endpoint(NostrRTCSocket socket, NostrRTCChannel channel, ControlledChannel provider) {
            this.socket = socket;
            this.channel = channel;
            this.provider = provider;
        }
    }

    private static final class Fixture implements AutoCloseable {

        final AtomicLong clock = new AtomicLong(1_000_000L);
        final NostrKeyPair keys = new NostrKeyPair();
        final NostrPool pool = new NostrPool();
        final AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("terminal-room-fixture");
        final RTCSettings settings = RTCSettings
            .getDefault(APPLICATION, PROTOCOL)
            .withSignalingRelays(List.of())
            .withStunServers(List.of());
        final NostrRTCLocalPeer local = new NostrRTCLocalPeer(settings, NostrKeyPairSigner.generate(), "local", keys, null);
        final NostrKeyPairSigner remoteSigner = NostrKeyPairSigner.generate();
        final NostrRTCRoom room = new NostrRTCRoom(settings, local, keys, pool, null, clock::get);
        final NostrRTCSocketListener listener = (NostrRTCSocketListener) field(room, "listener");
        final List<ControlledChannel> providers = new ArrayList<>();
        final List<Endpoint> endpoints = new ArrayList<>();
        final Endpoint first;

        Fixture() throws Exception {
            first = replacement("original-native");
        }

        Endpoint replacement(String nativeSession) throws Exception {
            NostrRTCPeer peer = new NostrRTCPeer(
                NGEUtils.awaitNoThrow(remoteSigner.getPublicKey()),
                APPLICATION,
                PROTOCOL,
                nativeSession,
                keys.getPublicKey(),
                null
            );
            NostrRTCSocket socket = new NostrRTCSocket(executor, peer, keys, local, settings, null);
            socket.addListener(listener);
            connections().put(peer, socket);
            NostrRTCChannel logical = room.createChannel(peer, CHANNEL, true, true);
            ControlledChannel provider = new ControlledChannel();
            providers.add(provider);
            logical.setChannel(provider);
            Endpoint endpoint = new Endpoint(socket, logical, provider);
            endpoints.add(endpoint);
            return endpoint;
        }

        @SuppressWarnings("unchecked")
        Map<NostrRTCPeer, NostrRTCSocket> connections() {
            return (Map<NostrRTCPeer, NostrRTCSocket>) field(room, "connections");
        }

        @SuppressWarnings("unchecked")
        BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue(NostrRTCChannel channel) {
            return (
                (Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>>) field(room, "pendingSends")
            ).get(channel);
        }

        Probe send(Endpoint endpoint) throws Exception {
            Probe probe = new Probe(room.send(endpoint.channel, ByteBuffer.wrap(new byte[] { 1 })), room);
            await(() -> !endpoint.provider.writes.isEmpty());
            return probe;
        }

        @Override
        public void close() {
            for (ControlledChannel provider : providers) {
                provider.closeRelease.countDown();
                provider.closeCompletion.succeed();
                for (Pending pending : provider.writes) pending.fail(new IllegalStateException("fixture cleanup"));
            }
            for (Endpoint endpoint : endpoints) endpoint.socket.close();
            room.close();
            pool.close();
            executor.close();
            keys.close();
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1L);
        assertTrue("Actual room/provider completion before fixture deadline", condition.getAsBoolean());
    }
}
