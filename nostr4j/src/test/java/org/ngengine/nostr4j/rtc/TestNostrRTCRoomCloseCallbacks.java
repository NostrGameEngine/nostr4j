/**
 * BSD 3-Clause License; Copyright (c) 2026, Riccardo Balbo.
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastContext;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCDataChannel;

/**
 * Real room/socket/channel teardown with the native provider's callback-draining close contract.
 */
public class TestNostrRTCRoomCloseCallbacks {

    @Test(timeout = 5000L)
    public void nativeCloseCanDrainAnEnteredBroadcastCallbackThatNeedsTheRoomMonitor() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BroadcastContext context = (BroadcastContext) get(get(fixture.room, "broadcastEngine"), "context");
            String graphId = fixture.room.getRoutingTopology().getSnapshotId();
            assertNotNull(context.graphBySnapshotId(graphId));
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            AtomicInteger disconnections = new AtomicInteger();
            fixture.room.addDisconnectionListener((peer, socket) -> disconnections.incrementAndGet());
            Thread callback = new Thread(
                () -> {
                    try {
                        assertTrue(fixture.nativeChannel.entered.await(2, TimeUnit.SECONDS));
                        // This is the exact room-owned context called by an already-entered onTreeFrame.
                        assertNull(
                            "Closed room must let the callback return without reusing a live topology snapshot",
                            context.graphBySnapshotId(graphId)
                        );
                        // A callback may observe close again; it cannot wait for its own drain.
                        fixture.room.close();
                    } catch (Throwable failure) {
                        callbackFailure.set(failure);
                    } finally {
                        fixture.nativeChannel.callbackReturned.countDown();
                    }
                },
                "room-close-broadcast-callback"
            );
            callback.setDaemon(true);
            callback.start();
            try {
                fixture.room.close();
            } finally {
                callback.join(2000L);
            }
            assertFalse(callback.isAlive());
            assertNull(callbackFailure.get());
            assertTrue(
                "Native close must drain the callback before returning, without owning its room monitor",
                fixture.nativeChannel.drainedBeforeReturn
            );
            assertEquals(1, fixture.nativeChannel.closes.get());
            assertEquals("Detaching teardown ownership must retain socket disconnection notification", 1, disconnections.get());
            assertTrue(fixture.room.getSockets().isEmpty());
            fixture.room.close();
            assertEquals(1, fixture.nativeChannel.closes.get());
        }
    }

    @Test(timeout = 5000L)
    public void lateAnnouncementAndSendCannotRepopulateResourcesWhileNativeCloseIsDraining() throws Exception {
        try (Fixture fixture = new Fixture()) {
            NostrRTCConnectSignal late = fixture.announce("late-peer");
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread closer = new Thread(
                () -> {
                    try {
                        fixture.room.close();
                    } catch (Throwable failure) {
                        closeFailure.set(failure);
                    }
                },
                "room-close-owner"
            );
            closer.setDaemon(true);
            closer.start();
            try {
                assertTrue(fixture.nativeChannel.entered.await(2, TimeUnit.SECONDS));
                fixture.deliver(late);
                assertNull(
                    "An already-scheduled announcement cannot create a post-close logical socket",
                    fixture.room.getSocket(late.getPeer())
                );
                try {
                    fixture.room.send(fixture.logical, ByteBuffer.wrap(new byte[] { 1 }));
                    fail("Closing room must reject new queue admission");
                } catch (IllegalStateException expected) {
                    assertEquals("Room is closed", expected.getMessage());
                }
                assertTrue(((Map<?, ?>) get(fixture.room, "pendingSends")).isEmpty());
            } finally {
                fixture.nativeChannel.callbackReturned.countDown();
                closer.join(2000L);
            }
            assertFalse(closer.isAlive());
            assertNull(closeFailure.get());
            assertTrue(fixture.room.getSockets().isEmpty());
            assertTrue(((Map<?, ?>) get(fixture.room, "pendingSends")).isEmpty());
        }
    }

    @Test(timeout = 5000L)
    public void alreadyAdmittedSendIsSettledByTheOwningClose() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Field nativeHandle = NostrRTCChannel.class.getDeclaredField("channel");
            nativeHandle.setAccessible(true);
            nativeHandle.set(fixture.logical, null);
            assertFalse(fixture.logical.isReady());
            AsyncTask<Void> send = fixture.room.send(fixture.logical, ByteBuffer.wrap(new byte[] { 1 }));
            Map<?, ?> queues = (Map<?, ?>) get(fixture.room, "pendingSends");
            assertEquals(1, queues.size());
            assertEquals(1, ((BlockingPacketQueue<?>) queues.values().iterator().next()).size());
            fixture.room.close();
            assertTrue("The synchronous teardown owner must reject pending sends before returning", send.isFailed());
            assertTrue(queues.isEmpty());
        }
    }

    private static final class Fixture implements AutoCloseable {

        final NostrKeyPair keys = new NostrKeyPair();
        final NostrPool pool = new NostrPool();
        final RTCSettings settings = RTCSettings
            .getDefault("close-app", "close-protocol")
            .withSignalingRelays(List.of())
            .withStunServers(List.of());
        final NostrRTCLocalPeer local = new NostrRTCLocalPeer(settings, NostrKeyPairSigner.generate(), "local", keys, null);
        final NostrRTCRoom room = new NostrRTCRoom(settings, local, keys, pool, null);
        final DrainingChannel nativeChannel = new DrainingChannel();
        final NostrRTCChannel logical;

        Fixture() throws Exception {
            NostrRTCConnectSignal remote = announce("remote");
            deliver(remote);
            logical = room.getSocket(remote.getPeer()).getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME);
            Field channel = NostrRTCChannel.class.getDeclaredField("channel");
            channel.setAccessible(true);
            channel.set(logical, nativeChannel);
        }

        NostrRTCConnectSignal announce(String session) {
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            NostrRTCPeer peer = new NostrRTCPeer(
                NGEUtils.awaitNoThrow(signer.getPublicKey()),
                "close-app",
                "close-protocol",
                session,
                keys.getPublicKey(),
                null
            );
            return new NostrRTCConnectSignal(signer, keys, peer, Instant.now().plusSeconds(60), "");
        }

        void deliver(NostrRTCConnectSignal signal) throws Exception {
            Method method = NostrRTCRoom.class.getDeclaredMethod("onAddAnnounce", NostrRTCConnectSignal.class);
            method.setAccessible(true);
            method.invoke(room, signal);
        }

        @Override
        public void close() {
            nativeChannel.callbackReturned.countDown();
            // Also clean up late sockets if the unpatched baseline fails the admission regression.
            for (NostrRTCSocket socket : room.getSockets()) socket.close();
            room.close();
            pool.close();
        }
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static final class DrainingChannel extends RTCDataChannel {

        final CountDownLatch entered = new CountDownLatch(1), callbackReturned = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        volatile boolean drainedBeforeReturn;

        DrainingChannel() {
            super(NostrRTCSocket.DEFAULT_CHANNEL_NAME, "close-protocol", true, true, 0, null);
        }

        @Override
        public AsyncTask<Void> close() {
            closes.incrementAndGet();
            entered.countDown();
            // Only the fixture is bounded: production close receives no timeout or early-success escape.
            try {
                drainedBeforeReturn = callbackReturned.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return AsyncTask.completed(null);
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
}
