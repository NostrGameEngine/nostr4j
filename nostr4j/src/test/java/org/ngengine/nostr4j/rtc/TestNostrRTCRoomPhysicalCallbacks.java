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
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastContext;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

/**
 * Offline room/physical-manager regressions. Provider close deliberately drains a
 * callback which needs the room monitor; no relay, native RTC, or TURN is used.
 */
public class TestNostrRTCRoomPhysicalCallbacks {

    @Test(timeout = 5000L)
    public void policyExclusionCanDrainAnEnteredBroadcastCallback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BroadcastContext context = fixture.broadcastContext();
            String graphId = fixture.room.getRoutingTopology().getSnapshotId();
            assertNotNull(context.graphBySnapshotId(graphId));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread callback = worker(
                "exclusion-broadcast-callback",
                failure,
                () -> {
                    assertTrue(fixture.old.closeEntered.await(2L, TimeUnit.SECONDS));
                    try {
                        assertNotNull(context.graphBySnapshotId(graphId));
                    } finally {
                        fixture.old.closeRelease.countDown();
                    }
                }
            );
            callback.start();
            try {
                fixture.exclude();
                join(callback);
                rethrow(failure);
                assertTrue("Native close must finish draining the callback", fixture.old.drained);
                assertFalse("Native cleanup must not inherit the room monitor", fixture.old.heldRoom);
                assertEquals(1, fixture.old.closes.get());
                assertFalse(fixture.socket.isPhysicalLinkEnabled());
                assertSame("Exclusion retains the logical endpoint", fixture.socket, fixture.room.getSocket(fixture.remote));
                assertEquals(0, fixture.manager.resources());
            } finally {
                fixture.old.closeRelease.countDown();
                join(callback);
            }
        }
    }

    @Test(timeout = 5000L)
    public void physicalReservationRemainsChargedUntilCallbackDrainCompletes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread exclusion = worker("physical-exclusion-owner", failure, fixture::exclude);
            exclusion.start();
            try {
                assertTrue(fixture.old.closeEntered.await(2L, TimeUnit.SECONDS));
                fixture.room.setDiscoveryPriority(peer -> 0f);
                fixture.refresh();
                assertEquals("Closing native resources still own their reservation", 1, fixture.manager.resources());
                assertNull("The same candidate cannot overlap its closing attempt", fixture.manager.admit(fixture.remote));
                assertFalse(fixture.socket.isPhysicalLinkEnabled());
                assertEquals(0, fixture.platform.created.get());
                fixture.old.closeRelease.countDown();
                join(exclusion);
                rethrow(failure);
                fixture.refresh();
                PhysicalConnectionManager.Attempt replacement = fixture.manager.admit(fixture.remote);
                assertNotNull("A fresh policy evaluation can admit after cleanup", replacement);
                assertNotSame(fixture.attempt, replacement);
                assertSame(fixture.socket, fixture.prepare(replacement));
                assertTrue(fixture.socket.isPhysicalLinkEnabled());
                assertEquals(1, fixture.old.closes.get());
            } finally {
                fixture.old.closeRelease.countDown();
                join(exclusion);
            }
        }
    }

    @Test(timeout = 5000L)
    public void oldPhysicalCleanupCannotRetireAReplacementLogicalSocket() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread exclusion = worker("retired-physical-cleanup", failure, fixture::exclude);
            exclusion.start();
            try {
                assertTrue(fixture.old.closeEntered.await(2L, TimeUnit.SECONDS));
                fixture.room.disconnect(fixture.remote);
                fixture.announce();
                NostrRTCSocket replacement = fixture.room.getSocket(fixture.remote);
                assertNotNull(replacement);
                assertNotSame(fixture.socket, replacement);
                fixture.old.closeRelease.countDown();
                join(exclusion);
                rethrow(failure);
                assertSame(replacement, fixture.room.getSocket(fixture.remote));
                assertFalse(replacement.isClosed());
                assertTrue(fixture.socket.isClosed());
                assertEquals(1, fixture.old.closes.get());
            } finally {
                fixture.old.closeRelease.countDown();
                join(exclusion);
            }
        }
    }

    @Test(timeout = 5000L)
    public void retirementDuringOfferCollisionCleanupCannotAllocateAnotherNativeTransport() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread offer = worker("offer-collision-cleanup", failure, fixture::offer);
            offer.start();
            try {
                assertTrue(fixture.old.closeEntered.await(2L, TimeUnit.SECONDS));
                fixture.room.disconnect(fixture.remote);
                fixture.old.closeRelease.countDown();
                join(offer);
                rethrow(failure);
                assertTrue(fixture.socket.isClosed());
                assertNull(fixture.room.getSocket(fixture.remote));
                assertFalse("Offer cleanup must not inherit the room monitor", fixture.old.heldRoom);
                assertEquals("The retired attempt cannot enter the native factory", 0, fixture.platform.created.get());
            } finally {
                fixture.old.closeRelease.countDown();
                join(offer);
            }
        }
    }

    @Test(timeout = 5000L)
    public void pendingNativeChannelCloseRetainsCapacityUntilItsCompletion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.old.closeRelease.countDown();
            PendingChannel channel = new PendingChannel();
            set(fixture.socket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME), "channel", channel);
            fixture.exclude();
            assertEquals(1, channel.closes.get());
            assertEquals(1, fixture.manager.resources());
            fixture.refresh();
            assertEquals("Maintenance must not close a captured handle twice", 1, channel.closes.get());
            channel.resolve.accept(null);
            await(() -> fixture.manager.resources() == 0);
            assertTrue(((java.util.Map<?, ?>) get(fixture.room, "physicalRetirements")).isEmpty());
            assertEquals(1, fixture.old.closes.get());
        }
    }

    @Test(timeout = 5000L)
    public void failedNativeCloseIsQuarantinedWhileOtherHandlesAndPeersStillClose() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.old.closeRelease.countDown();
            IllegalStateException failure = new IllegalStateException("controlled native close failure");
            fixture.old.closeFailure = failure;
            PendingChannel channel = new PendingChannel();
            channel.resolve.accept(null);
            set(fixture.socket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME), "channel", channel);
            Transport other = fixture.addPhysicalPeer("second");
            fixture.exclude();
            assertEquals(1, fixture.old.closes.get());
            assertEquals("Other captured handles still close", 1, channel.closes.get());
            assertEquals("Other retiring peers still close", 1, other.closes.get());
            assertEquals("The uncertain provider still owns one capacity slot", 1, fixture.manager.resources());
            PhysicalResourceRetirement owned = (PhysicalResourceRetirement) (
                (java.util.Map<?, ?>) get(fixture.room, "physicalRetirements")
            ).get(fixture.attempt);
            assertNotNull(owned);
            assertTrue(owned.isSettled());
            assertFalse(owned.isSuccessful());
            assertSame(failure, owned.getFailure());
            fixture.refresh();
            fixture.room.close();
            assertEquals("Maintenance/close must not blindly retry a partially closed provider", 1, fixture.old.closes.get());
            assertEquals(1, other.closes.get());
            assertEquals(1, fixture.manager.resources());
        }
    }

    @Test(timeout = 5000L)
    public void failedTurnReceiveCloseDoesNotSkipTheSendHandle() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.old.closeRelease.countDown();
            NostrRTCChannel logical = fixture.socket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME);
            NostrTURNChannel receive = new NostrTURNChannel(
                fixture.socket.getLocalPeer(),
                fixture.remote,
                "ws://offline-turn.invalid",
                fixture.keys,
                "receive",
                true,
                24
            );
            Executor receiveAck = fixture.platform.turnExecutors.get(0);
            receiveAck.closeFailure = new IllegalStateException("controlled TURN executor close failure");
            NostrTURNChannel send = new NostrTURNChannel(
                fixture.socket.getLocalPeer(),
                fixture.remote,
                "ws://offline-turn.invalid",
                fixture.keys,
                "send",
                true,
                24
            );
            set(logical, "turnReceive", receive);
            set(logical, "turnSend", send);
            fixture.exclude();
            assertTrue(receive.isClosed());
            assertTrue("The independently captured send handle must still close", send.isClosed());
            assertEquals(1, receiveAck.closes.get());
            assertEquals(1, fixture.manager.resources());
            fixture.refresh();
            fixture.room.close();
            assertEquals("Uncertain TURN disposal is quarantined, not retried", 1, receiveAck.closes.get());
            assertEquals(1, fixture.manager.resources());
        }
    }

    @Test(timeout = 5000L)
    public void disconnectCannotBypassFailedPhysicalRetirementAccounting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.old.closeRelease.countDown();
            fixture.old.closeFailure = new IllegalStateException("controlled disconnect close failure");
            fixture.room.disconnect(fixture.remote);
            assertTrue(fixture.socket.isClosed());
            assertNull(fixture.room.getSocket(fixture.remote));
            assertEquals(1, fixture.manager.resources());
            fixture.refresh();
            fixture.room.close();
            assertEquals(1, fixture.old.closes.get());
            assertEquals(1, fixture.manager.resources());
        }
    }

    @Test(timeout = 5000L)
    public void failedNativeChannelCloseFutureCannotReleaseCapacity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.old.closeRelease.countDown();
            PendingChannel channel = new PendingChannel();
            set(fixture.socket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME), "channel", channel);
            fixture.exclude();
            PhysicalResourceRetirement owned = (PhysicalResourceRetirement) (
                (java.util.Map<?, ?>) get(fixture.room, "physicalRetirements")
            ).get(fixture.attempt);
            IllegalStateException failure = new IllegalStateException("controlled async close failure");
            channel.reject.accept(failure);
            await(owned::isSettled);
            assertFalse(owned.isSuccessful());
            assertSame(failure, owned.getFailure());
            assertEquals(1, fixture.manager.resources());
            fixture.refresh();
            fixture.room.close();
            assertEquals(1, channel.closes.get());
            assertEquals(1, fixture.manager.resources());
        }
    }

    @Test(timeout = 5000L)
    public void timeoutCleanupCannotObserveAnOfferOwnershipPublicationGap() throws Exception {
        try (Fixture fixture = new Fixture()) {
            OwnershipPublicationBarrier owners = new OwnershipPublicationBarrier(fixture.attempt);
            owners.put(fixture.attempt, fixture.socket);
            set(fixture.room, "physicalAttemptSockets", owners);
            ((java.util.concurrent.atomic.AtomicBoolean) get(fixture.room, "directRefreshRunning")).set(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread offer = worker("yield-owner-publication", failure, fixture::offer);
            Thread cleanup = worker(
                "timeout-owner-consumer",
                failure,
                () -> invoke(fixture.room, "cleanupPhysicalConnections")
            );
            offer.start();
            try {
                assertTrue(owners.entered.await(2L, TimeUnit.SECONDS));
                PhysicalConnectionManager.Attempt incoming = fixture.manager.attempt(fixture.remote);
                assertNotNull(incoming);
                assertNotSame(fixture.attempt, incoming);
                set(fixture.manager, "clock", (java.util.function.LongSupplier) () -> incoming.startedAt + 300000L);
                fixture.manager.reconcile(
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptySet()
                );
                cleanup.start();
                await(() -> cleanup.getState() == Thread.State.BLOCKED);
                assertEquals(
                    "The unpublished owner cannot be mistaken for an empty reservation",
                    1,
                    fixture.manager.resources()
                );
                assertEquals(0, fixture.old.closes.get());
                owners.release.countDown();
                assertTrue(fixture.old.closeEntered.await(2L, TimeUnit.SECONDS));
                assertEquals("The captured predecessor remains charged while close drains", 1, fixture.manager.resources());
                assertTrue(((java.util.Map<?, ?>) get(fixture.room, "physicalRetirements")).containsKey(incoming));
                fixture.old.closeRelease.countDown();
                join(cleanup);
                join(offer);
                rethrow(failure);
                assertEquals(1, fixture.old.closes.get());
                assertEquals(0, fixture.manager.resources());
                assertEquals(0, fixture.platform.created.get());
            } finally {
                owners.release.countDown();
                fixture.old.closeRelease.countDown();
                join(offer);
                if (cleanup.getState() != Thread.State.NEW) join(cleanup);
            }
        }
    }

    @Test(timeout = 5000L)
    public void failedPredecessorCloseDuringOfferCollisionCannotAllocateItsReplacement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.old.closeRelease.countDown();
            fixture.old.closeFailure = new IllegalStateException("controlled collision predecessor failure");
            fixture.offer();
            assertEquals(1, fixture.old.closes.get());
            assertEquals("Failed predecessor cannot share its slot with a replacement", 0, fixture.platform.created.get());
            assertEquals(1, fixture.manager.resources());
            assertEquals(1, ((java.util.Map<?, ?>) get(fixture.room, "physicalRetirements")).size());
            fixture.refresh();
            fixture.room.close();
            assertEquals(1, fixture.old.closes.get());
            assertEquals(1, fixture.manager.resources());
        }
    }

    @Test(timeout = 5000L)
    public void unresolvedAndFailedChannelCloseDuringCollisionBlocksReplacementAllocation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.old.closeRelease.countDown();
            PendingChannel channel = new PendingChannel();
            set(fixture.socket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME), "channel", channel);
            fixture.offer();
            assertEquals(1, channel.closes.get());
            assertEquals(0, fixture.platform.created.get());
            assertEquals(1, fixture.manager.resources());
            channel.reject.accept(new IllegalStateException("controlled collision channel failure"));
            java.util.Map<?, ?> retirements = (java.util.Map<?, ?>) get(fixture.room, "physicalRetirements");
            await(() ->
                retirements
                    .values()
                    .stream()
                    .map(PhysicalResourceRetirement.class::cast)
                    .allMatch(PhysicalResourceRetirement::isSettled)
            );
            fixture.refresh();
            fixture.room.close();
            assertEquals(1, channel.closes.get());
            assertEquals(1, fixture.old.closes.get());
            assertEquals(0, fixture.platform.created.get());
            assertEquals(1, fixture.manager.resources());
        }
    }

    @Test(timeout = 5000L)
    public void timeoutDuringNativeFactoryKeepsCapacityUntilLateHandleCloses() throws Exception {
        factoryRetirement(false);
    }

    @Test(timeout = 5000L)
    public void failedLateFactoryHandleCloseRemainsOwnedAfterRoomClose() throws Exception {
        factoryRetirement(true);
    }

    private void factoryRetirement(boolean failClose) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.old.closeRelease.countDown();
            fixture.old.close();
            set(fixture.socket, "transport", null);
            Transport late = new Transport(fixture.room);
            late.closeRelease.countDown();
            if (failClose) late.closeFailure = new IllegalStateException("controlled late factory close failure");
            fixture.platform.factoryReturn = late;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread allocation = worker("native-factory-in-flight", failure, fixture::beginOffer);
            allocation.start();
            try {
                assertTrue(fixture.platform.factoryEntered.await(2L, TimeUnit.SECONDS));
                set(fixture.manager, "clock", (java.util.function.LongSupplier) () -> fixture.attempt.startedAt + 300000L);
                fixture.manager.reconcile(
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptySet()
                );
                invoke(fixture.room, "cleanupPhysicalConnections");
                assertEquals("A possible late native handle still owns capacity", 1, fixture.manager.resources());
                assertEquals(0, late.closes.get());
                fixture.room.close();
                assertEquals(1, fixture.manager.resources());
                fixture.platform.factoryRelease.countDown();
                join(allocation);
                rethrow(failure);
                assertEquals(1, late.closes.get());
                assertFalse(late.heldRoom);
                assertEquals(failClose ? 1 : 0, fixture.manager.resources());
                if (failClose) {
                    assertEquals(1, ((java.util.Map<?, ?>) get(fixture.room, "physicalRetirements")).size());
                    assertEquals(1, ((java.util.List<?>) get(fixture.socket, "nativeAllocations")).size());
                }
                fixture.room.close();
                assertEquals(1, late.closes.get());
                assertEquals(1, fixture.platform.created.get());
            } finally {
                fixture.platform.factoryRelease.countDown();
                join(allocation);
            }
        }
    }

    private static final class OwnershipPublicationBarrier
        extends java.util.concurrent.ConcurrentHashMap<PhysicalConnectionManager.Attempt, NostrRTCSocket> {

        final PhysicalConnectionManager.Attempt original;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        OwnershipPublicationBarrier(PhysicalConnectionManager.Attempt original) {
            this.original = original;
        }

        @Override
        public NostrRTCSocket put(PhysicalConnectionManager.Attempt attempt, NostrRTCSocket socket) {
            if (attempt != original) {
                entered.countDown();
                try {
                    assertTrue("The fixture must release ownership publication", release.await(2L, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return super.put(attempt, socket);
        }
    }

    private static final class Fixture implements AutoCloseable {

        final NGEPlatform previousPlatform;
        final Platform platform = new Platform();
        final NostrKeyPair keys;
        final NostrPool pool;
        final NostrRTCRoom room;
        final NostrRTCPeer remote;
        final NostrKeyPairSigner remoteSigner;
        final AsyncExecutor originalExecutor;
        final Executor executor = new Executor();
        final PhysicalConnectionManager manager;
        final PhysicalConnectionManager.Attempt attempt;
        final NostrRTCSocket socket;
        final Transport old;

        Fixture() throws Exception {
            Field global = NGEPlatform.class.getDeclaredField("platform");
            global.setAccessible(true);
            previousPlatform = (NGEPlatform) global.get(null);
            global.set(null, platform);
            keys = new NostrKeyPair();
            pool = new NostrPool();
            RTCSettings settings = RTCSettings
                .getDefault("physical-callback-app", "physical-callback-protocol")
                .withSignalingRelays(List.of())
                .withStunServers(List.of());
            NostrKeyPairSigner localSigner = NostrKeyPairSigner.generate();
            NostrKeyPairSigner otherSigner = NostrKeyPairSigner.generate();
            // The larger key yields an outgoing reservation to the simultaneous remote offer.
            if (
                NGEUtils
                    .awaitNoThrow(localSigner.getPublicKey())
                    .asHex()
                    .compareTo(NGEUtils.awaitNoThrow(otherSigner.getPublicKey()).asHex()) <
                0
            ) {
                NostrKeyPairSigner swap = localSigner;
                localSigner = otherSigner;
                otherSigner = swap;
            }
            remoteSigner = otherSigner;
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(settings, localSigner, "local", keys, null);
            remote =
                new NostrRTCPeer(
                    NGEUtils.awaitNoThrow(remoteSigner.getPublicKey()),
                    "physical-callback-app",
                    "physical-callback-protocol",
                    "remote",
                    keys.getPublicKey(),
                    null
                );
            room = new NostrRTCRoom(settings, local, keys, pool, null);
            originalExecutor = (AsyncExecutor) get(room, "executor");
            set(room, "executor", executor);
            announce();
            socket = room.getSocket(remote);
            assertNotNull(socket);
            manager = (PhysicalConnectionManager) get(room, "physicalConnections");
            List<PhysicalConnectionManager.Attempt> outgoing = manager.fill();
            assertEquals(1, outgoing.size());
            attempt = outgoing.get(0);
            assertTrue(attempt.outgoing);
            assertSame(socket, prepare(attempt));
            old = new Transport(room);
            set(socket, "transport", old);
        }

        void announce() throws Exception {
            invoke(
                room,
                "onAddAnnounce",
                NostrRTCConnectSignal.class,
                new NostrRTCConnectSignal(remoteSigner, keys, remote, Instant.now().plusSeconds(60L), "")
            );
        }

        void exclude() throws Exception {
            room.setDiscoveryPriority(peer -> -1f);
            refresh();
        }

        void refresh() throws Exception {
            Method method = NostrRTCRoom.class.getDeclaredMethod("refreshDirectNeighbors");
            method.setAccessible(true);
            method.invoke(room);
        }

        NostrRTCSocket prepare(PhysicalConnectionManager.Attempt value) throws Exception {
            return (NostrRTCSocket) invoke(room, "preparePhysicalConnection", PhysicalConnectionManager.Attempt.class, value);
        }

        void offer() throws Exception {
            invoke(
                room,
                "onReceiveOffer",
                NostrRTCOfferSignal.class,
                new NostrRTCOfferSignal(remoteSigner, keys, remote, "offline-offer")
            );
        }

        void beginOffer() throws Exception {
            Method method =
                NostrRTCRoom.class.getDeclaredMethod(
                        "createPhysicalOffer",
                        PhysicalConnectionManager.Attempt.class,
                        NostrRTCSocket.class
                    );
            method.setAccessible(true);
            method.invoke(room, attempt, socket);
        }

        BroadcastContext broadcastContext() throws Exception {
            return (BroadcastContext) get(get(room, "broadcastEngine"), "context");
        }

        Transport addPhysicalPeer(String session) throws Exception {
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            NostrRTCPeer peer = new NostrRTCPeer(
                NGEUtils.awaitNoThrow(signer.getPublicKey()),
                "physical-callback-app",
                "physical-callback-protocol",
                session,
                keys.getPublicKey(),
                null
            );
            invoke(
                room,
                "onAddAnnounce",
                NostrRTCConnectSignal.class,
                new NostrRTCConnectSignal(signer, keys, peer, Instant.now().plusSeconds(60L), "")
            );
            PhysicalConnectionManager.Attempt other = manager.admit(peer);
            assertNotNull(other);
            NostrRTCSocket otherSocket = prepare(other);
            assertNotNull(otherSocket);
            Transport transport = new Transport(room);
            transport.closeRelease.countDown();
            set(otherSocket, "transport", transport);
            return transport;
        }

        @Override
        public void close() throws Exception {
            old.closeRelease.countDown();
            try {
                room.close();
                pool.close();
                originalExecutor.close();
                keys.close();
            } finally {
                Field global = NGEPlatform.class.getDeclaredField("platform");
                global.setAccessible(true);
                global.set(null, previousPlatform);
            }
        }
    }

    private static final class Executor implements AsyncExecutor {

        final List<Callable<?>> scheduled = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        RuntimeException closeFailure;

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
        public void close() {
            closes.incrementAndGet();
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class Platform extends JVMAsyncPlatform {

        final AtomicInteger created = new AtomicInteger();
        final List<Executor> turnExecutors = new ArrayList<>();
        final CountDownLatch factoryEntered = new CountDownLatch(1), factoryRelease = new CountDownLatch(1);
        volatile RTCTransport factoryReturn;

        @Override
        public AsyncExecutor newAsyncExecutor(Object key) {
            if (key instanceof String && ((String) key).startsWith("NostrTURNChannel-")) {
                Executor executor = new Executor();
                turnExecutors.add(executor);
                return executor;
            }
            return super.newAsyncExecutor(key);
        }

        @Override
        public RTCTransport newRTCTransport(Duration timeout, String session, Collection<String> stun) {
            created.incrementAndGet();
            RTCTransport result = factoryReturn;
            if (result != null) {
                factoryEntered.countDown();
                try {
                    assertTrue("The fixture must release its provider factory", factoryRelease.await(2L, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return result;
            }
            throw new AssertionError("This offline fixture must never allocate a native RTC transport");
        }
    }

    private static final class Transport implements RTCTransport {

        final NostrRTCRoom room;
        final CountDownLatch closeEntered = new CountDownLatch(1), closeRelease = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        volatile boolean drained, heldRoom;
        RuntimeException closeFailure;

        Transport(NostrRTCRoom room) {
            this.room = room;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            heldRoom = Thread.holdsLock(room);
            closeEntered.countDown();
            // Only the fixture is bounded; production still waits for provider close normally.
            try {
                drained = closeRelease.await(2L, TimeUnit.SECONDS);
                if (closeFailure != null) throw closeFailure;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }

        @Override
        public void start(Duration timeout, AsyncExecutor executor, String id, Collection<String> stun) {}

        @Override
        public void addListener(RTCTransportListener listener) {}

        @Override
        public void removeListener(RTCTransportListener listener) {}

        @Override
        public String getName() {
            return "offline-callback-draining-transport";
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public AsyncTask<String> listen() {
            return AsyncTask.failed(new AssertionError("Offline fixture"));
        }

        @Override
        public AsyncTask<String> connect(String sdp) {
            return AsyncTask.failed(new AssertionError("Offline fixture"));
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
            return AsyncTask.failed(new AssertionError("Offline fixture"));
        }
    }

    private static final class PendingChannel extends RTCDataChannel {

        final AtomicInteger closes = new AtomicInteger();
        Consumer<Void> resolve;
        Consumer<Throwable> reject;
        final AsyncTask<Void> completion;

        PendingChannel() {
            super(NostrRTCSocket.DEFAULT_CHANNEL_NAME, "physical-callback-protocol", true, true, 0, null);
            completion =
                AsyncTask.create((accept, fail) -> {
                    resolve = accept;
                    reject = fail;
                });
        }

        @Override
        public AsyncTask<Void> close() {
            closes.incrementAndGet();
            return completion;
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer data) {
            return AsyncTask.completed(null);
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

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1L);
        assertTrue("Expected retirement completion before fixture deadline", condition.getAsBoolean());
    }

    private interface Action {
        void run() throws Exception;
    }

    private static Thread worker(String name, AtomicReference<Throwable> failure, Action action) {
        Thread thread = new Thread(
            () -> {
                try {
                    action.run();
                } catch (Throwable error) {
                    failure.set(error);
                }
            },
            name
        );
        thread.setDaemon(true);
        return thread;
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(1000L);
        assertFalse("Fixture worker must be joined", thread.isAlive());
    }

    private static void rethrow(AtomicReference<Throwable> failure) {
        if (failure.get() != null) throw new AssertionError("Fixture worker failed", failure.get());
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Object invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static Object invoke(Object owner, String name, Class<?> type, Object value) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(owner, value);
    }
}
