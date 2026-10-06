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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrTURNPool.TURNTransport;
import org.ngengine.nostr4j.rtc.delivery.AcknowledgedDeliveryTracker;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class TestNostrTURNAckRetry {

    private static final String APPLICATION_ID = "turn-ack-retry-app";
    private static final String PROTOCOL_ID = "turn-ack-retry-proto";
    private static final String CHANNEL = "primary";

    @Test(timeout = 10000L)
    public void replayedAckCannotCompleteAnotherPendingWrite() throws Exception {
        NostrKeyPair room = new NostrKeyPair();
        NostrRTCLocalPeer alice = localPeer("receipt-alice", room);
        NostrRTCLocalPeer bob = localPeer("receipt-bob", room);
        NostrTURNChannel sender = new NostrTURNChannel(
            alice,
            remotePeer(bob, room),
            "ws://linked.test/turn",
            room,
            CHANNEL,
            true,
            32
        );
        NostrTURNChannel receiver = new NostrTURNChannel(
            bob,
            remotePeer(alice, room),
            "ws://linked.test/turn",
            room,
            CHANNEL,
            true,
            32
        );
        LinkedWebsocketTransport out = new LinkedWebsocketTransport();
        LinkedWebsocketTransport back = new LinkedWebsocketTransport();
        try {
            setLongField(receiver, "vSocketId", sender.getRoutingVsocketId());
            out.target = receiver;
            back.target = sender;
            sender.setTransport(new TURNTransport(out));
            receiver.setTransport(new TURNTransport(back));
            setIntField(sender, "state", 2);
            setIntField(receiver, "state", 2);
            assertTrue(sender.write(ByteBuffer.wrap(new byte[] { 1 })).await());
            ByteBuffer oldAck = back.lastAckFrame;
            out.blockData.set(true);
            CountDownLatch completed = new CountDownLatch(1);
            sender
                .write(ByteBuffer.wrap(new byte[] { 2 }))
                .then(done -> {
                    completed.countDown();
                    return null;
                });
            long deadline = System.currentTimeMillis() + 3000;
            while (out.dataFrames.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertEquals(2, out.dataFrames.get());
            ByteBuffer pendingFrame = out.lastDataFrame;
            int id = NostrTURNCodec.extractMessageId(pendingFrame);
            sender.onBinaryMessage(NostrTURNCodec.withVsocketIdAndMessageId(oldAck, sender.getRoutingVsocketId(), id));
            org.junit.Assert.assertFalse(
                "Replayed ACK must not complete the pending write",
                completed.await(250, TimeUnit.MILLISECONDS)
            );
            receiver.onBinaryMessage(pendingFrame);
            assertTrue("Genuine authenticated receipt must complete", completed.await(3, TimeUnit.SECONDS));
        } finally {
            out.connected.set(false);
            back.connected.set(false);
            sender.close("test-cleanup");
            receiver.close("test-cleanup");
        }
    }

    @Test(timeout = 25_000L)
    public void testLostDeliveryAckRetriesSamePacketAndRegeneratesAckWithoutDuplicateDelivery() throws Exception {
        try (RoomTurnFixture fixture = new RoomTurnFixture(System::currentTimeMillis)) {
            fixture.back.deliveryAcksToDrop.set(1);
            fixture.send(ByteBuffer.wrap("lost-delivery-ack".getBytes(StandardCharsets.UTF_8)));
            waitUntil(() -> fixture.out.dataFrames.get() == 1, 3000L);
            Object originalEntry = queueHead(fixture.queue);
            Object originalPacket = field(originalEntry, "packet");
            long originalTime = longField(originalEntry, "enqueuedAtMs");
            long originalEpoch = longField(fixture.queue, "epoch");
            assertProductionLimits(fixture);
            // The real default 12 s authenticated receipt timer drives this retry.
            assertTrue("sender did not complete after retrying the lost ACK", fixture.completed.await(18, TimeUnit.SECONDS));
            assertEquals(2, fixture.out.dataFrames.get());
            assertEquals(2, fixture.receivedPacketIds.size());
            assertEquals(fixture.receivedPacketIds.get(0), fixture.receivedPacketIds.get(1));
            assertArrayEquals(fixture.receivedFrames.get(0), fixture.receivedFrames.get(1));
            assertFalse("each retry needs a fresh transport message", fixture.out.messageIds.get(0).equals(fixture.out.messageIds.get(1)));
            assertEquals("exactly one pause/retry epoch, no premature watchdog restart", originalEpoch + 1L, longField(fixture.queue, "epoch"));
            assertEquals("actual retry must use the original prepared packet", originalPacket, fixture.sentPreparedPackets.get(0));
            assertEquals("actual retry must use the original prepared packet", originalPacket, fixture.sentPreparedPackets.get(1));
            assertEquals("actual retry must preserve the original lifetime", Long.valueOf(originalTime), fixture.sentEnqueuedTimes.get(0));
            assertEquals("actual retry must preserve the original lifetime", Long.valueOf(originalTime), fixture.sentEnqueuedTimes.get(1));
            assertEquals(1, fixture.applicationDeliveries.get());
            assertEquals(1, fixture.successes.get());
            assertEquals(0, fixture.failures.get());
            assertEquals(2, fixture.back.deliveryAckFrames.get());
            assertEquals(0, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    @Test(timeout = 15_000L)
    public void delayedAuthenticatedReceiptAfterSixSecondsCompletesOriginalRoomAttempt() throws Exception {
        try (RoomTurnFixture fixture = new RoomTurnFixture(System::currentTimeMillis)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            assertProductionLimits(fixture);
            long originalEpoch = longField(fixture.queue, "epoch");
            Thread.sleep(7500L);
            fixture.queue.loop();
            assertEquals("receipt is still allowed before its 12 s deadline", originalEpoch, longField(fixture.queue, "epoch"));
            assertEquals(1, fixture.out.dataFrames.get());
            assertEquals(0, fixture.successes.get());
            fixture.back.releaseAck(0);
            assertTrue(fixture.completed.await(3, TimeUnit.SECONDS));
            assertEquals(1, fixture.applicationDeliveries.get());
            assertEquals(1, fixture.successes.get());
            assertEquals(0, fixture.failures.get());
            assertEquals(0, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    @Test(timeout = 10_000L)
    public void authenticatedSequentialFragmentsMayFinishAfterFifteenSecondsWithinOriginalLifetime() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[NostrRTCChannel.MAX_APPLICATION_FRAGMENT_SIZE * 2 + 1]));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            assertProductionLimits(fixture);
            long originalEpoch = longField(fixture.queue, "epoch");
            Object originalEntry = queueHead(fixture.queue);
            for (int fragment = 0; fragment < 3; fragment++) {
                waitUntil(() -> fixture.back.heldAcks.size() == fixture.out.dataFrames.get(), 3000L);
                assertEquals(fragment + 1, fixture.out.dataFrames.get());
                clock.addAndGet(9000L);
                fixture.queue.loop();
                assertEquals("still-live original chain must keep its epoch", originalEpoch, longField(fixture.queue, "epoch"));
                assertEquals(originalEntry, queueHead(fixture.queue));
                fixture.back.releaseAck(fragment);
                if (fragment < 2) {
                    final int expected = fragment + 2;
                    waitUntil(() -> fixture.back.heldAcks.size() == expected, 3000L);
                }
            }
            assertTrue(fixture.completed.await(3, TimeUnit.SECONDS));
            assertEquals("controlled queue clock models 27 s, below unchanged 30 s TTL", 1_027_000L, clock.get());
            assertEquals(3, fixture.out.dataFrames.get());
            assertEquals(1, fixture.applicationDeliveries.get());
            assertEquals(1, fixture.successes.get());
            assertEquals(0, fixture.failures.get());
            assertEquals(0, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    @Test(timeout = 10_000L)
    public void absoluteExpiryStopsLateFragmentsAndCannotCompleteReplacementHead() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[NostrRTCChannel.MAX_APPLICATION_FRAGMENT_SIZE * 2 + 1]));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            clock.addAndGet(30_000L);
            fixture.queue.loop();
            waitUntil(() -> fixture.failures.get() == 1, 3000L);
            assertEquals(0, fixture.queue.size());
            fixture.send(ByteBuffer.wrap(new byte[] { 9 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 2, 3000L);
            Object replacement = queueHead(fixture.queue);
            fixture.back.releaseAck(0);
            // Wait for the old authentic receipt to clear its tracker entry.
            waitUntil(() -> fixture.pendingReceipts() == 1, 3000L);
            assertEquals("expired chain cannot emit its next fragment", 2, fixture.out.dataFrames.get());
            assertEquals(replacement, queueHead(fixture.queue));
            assertEquals(1, fixture.queue.size());
            assertEquals(0, fixture.successes.get());
            fixture.back.releaseAck(1);
            waitUntil(() -> fixture.successes.get() == 1, 3000L);
            assertEquals(1, fixture.failures.get());
            assertEquals(1, fixture.applicationDeliveries.get());
            assertEquals(0, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    @Test(timeout = 10_000L)
    public void completionAtAbsoluteDeadlineFailsClosed() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 7 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            clock.addAndGet(30_000L);
            fixture.back.releaseAck(0);
            waitUntil(() -> fixture.failures.get() == 1, 3000L);
            assertEquals(0, fixture.successes.get());
            assertEquals(0, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    @Test(timeout = 10_000L)
    public void transportReplacementRetryDoesNotRenewOriginalItemDeadline() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 4 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            Object originalEntry = queueHead(fixture.queue);
            clock.addAndGet(20_000L);
            fixture.sender.setTransport(new TURNTransport(fixture.out));
            setIntField(fixture.sender, "state", 2);
            waitUntil(() -> field(fixture.queue, "executionQueue") == null, 3000L);
            fixture.queue.loop();
            waitUntil(() -> fixture.back.heldAcks.size() == 2, 3000L);
            assertEquals(originalEntry, queueHead(fixture.queue));
            assertArrayEquals(fixture.receivedFrames.get(0), fixture.receivedFrames.get(1));
            assertFalse(fixture.out.messageIds.get(0).equals(fixture.out.messageIds.get(1)));
            fixture.back.releaseAck(0);
            assertEquals(0, fixture.successes.get());
            clock.addAndGet(10_000L);
            fixture.queue.loop();
            waitUntil(() -> fixture.failures.get() == 1, 3000L);
            fixture.back.releaseAck(1);
            waitUntil(() -> fixture.pendingReceipts() == 0, 3000L);
            assertEquals(0, fixture.successes.get());
            assertEquals(1, fixture.failures.get());
            assertEquals(1, fixture.applicationDeliveries.get());
            assertEquals(0, fixture.queue.size());
        }
    }

    @Test(timeout = 10_000L)
    public void roomCloseRejectsCallerAndLateReceiptCannotEmitMoreFragments() throws Exception {
        try (RoomTurnFixture fixture = new RoomTurnFixture(System::currentTimeMillis)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[NostrRTCChannel.MAX_APPLICATION_FRAGMENT_SIZE * 2 + 1]));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            fixture.room.close();
            waitUntil(() -> fixture.failures.get() == 1, 3000L);
            fixture.back.releaseAck(0);
            assertEquals(1, fixture.out.dataFrames.get());
            assertEquals(0, fixture.successes.get());
            assertEquals(1, fixture.failures.get());
            assertEquals(0, fixture.applicationDeliveries.get());
            assertEquals(0, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    private static void assertProductionLimits(RoomTurnFixture fixture) throws Exception {
        assertEquals(1000L, longField(fixture.queue, "watchdogIntervalMs"));
        assertEquals(6000L, longField(fixture.queue, "stuckTimeoutMs"));
        assertEquals(30_000L, longField(fixture.queue, "queueItemTimeoutMs"));
        assertEquals(12_000L, longField(field(fixture.sender, "deliveryTracker"), "timeoutMs"));
        assertEquals(4096, ((Integer) field(field(fixture.sender, "deliveryTracker"), "maxPending")).intValue());
        assertEquals(65_503, NostrRTCChannel.MAX_FRAMED_PAYLOAD_SIZE);
        assertEquals(65_491, NostrRTCChannel.MAX_APPLICATION_FRAGMENT_SIZE);
        assertEquals(1024, NostrRTCChannel.MAX_FRAGMENTS_PER_PACKET);
        assertEquals(64, NostrRTCChannel.MAX_PENDING_FRAGMENT_PACKETS);
        assertEquals(16 * 1024 * 1024, NostrRTCChannel.MAX_REASSEMBLY_BYTES);
    }

    private static final class RoomTurnFixture implements AutoCloseable {
        private final AsyncExecutor aliceExecutor = NGEPlatform.get().newAsyncExecutor("room-ack-alice");
        private final AsyncExecutor bobExecutor = NGEPlatform.get().newAsyncExecutor("room-ack-bob");
        private final LinkedWebsocketTransport out = new LinkedWebsocketTransport();
        private final LinkedWebsocketTransport back = new LinkedWebsocketTransport();
        private final AtomicInteger applicationDeliveries = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final CountDownLatch completed = new CountDownLatch(1);
        private final List<Long> receivedPacketIds = new CopyOnWriteArrayList<Long>();
        private final List<byte[]> receivedFrames = new CopyOnWriteArrayList<byte[]>();
        private final List<Object> sentPreparedPackets = new CopyOnWriteArrayList<Object>();
        private final List<Long> sentEnqueuedTimes = new CopyOnWriteArrayList<Long>();
        private final NostrRTCRoom room;
        private final NostrRTCSocket aliceSocket;
        private final NostrRTCSocket bobSocket;
        private final NostrRTCChannel aliceLogical;
        private final NostrRTCChannel bobLogical;
        private final NostrTURNChannel sender;
        private final NostrTURNChannel receiver;
        private BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue;

        private RoomTurnFixture(LongSupplier clock) throws Exception {
            NostrKeyPair keys = new NostrKeyPair();
            NostrRTCLocalPeer alice = localPeer("room-alice", keys);
            NostrRTCLocalPeer bob = localPeer("room-bob", keys);
            RTCSettings settings = RTCSettings.getDefault(APPLICATION_ID, PROTOCOL_ID)
                .withSignalingRelays(Collections.emptyList()).withStunServers(Collections.emptyList());
            room = new NostrRTCRoom(settings, alice, keys, new NostrPool(), null, clock);
            aliceSocket = new NostrRTCSocket(aliceExecutor, remotePeer(bob, keys), keys, alice, settings, null);
            bobSocket = new NostrRTCSocket(bobExecutor, remotePeer(alice, keys), keys, bob, settings, null);
            aliceSocket.setForceTURN(true);
            aliceLogical = aliceSocket.createChannel(CHANNEL);
            bobLogical = bobSocket.createChannel(CHANNEL);
            sender = new NostrTURNChannel(alice, remotePeer(bob, keys), "ws://linked.test/turn", keys, CHANNEL, true, 32);
            receiver = new NostrTURNChannel(bob, remotePeer(alice, keys), "ws://linked.test/turn", keys, CHANNEL, true, 32);
            setLongField(receiver, "vSocketId", sender.getRoutingVsocketId());
            out.target = receiver;
            back.target = sender;
            sender.setTransport(new TURNTransport(out));
            receiver.setTransport(new TURNTransport(back));
            setIntField(sender, "state", 2);
            setIntField(receiver, "state", 2);
            setField(aliceLogical, "turnSend", sender);
            setField(aliceLogical, "turnReceive", sender);
            connections(room).put(aliceSocket.getRemotePeer(), aliceSocket);
            out.dataObserver = () -> {
                try {
                    Object entry = queueHead(pendingSends(room).get(aliceLogical));
                    sentPreparedPackets.add(field(entry, "packet"));
                    sentEnqueuedTimes.add(Long.valueOf(longField(entry, "enqueuedAtMs")));
                } catch (Exception error) {
                    throw new IllegalStateException("could not observe production queue entry", error);
                }
            };
            bobLogical.addListener(new NostrRTCChannelListener() {
                @Override public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer payload, boolean turn) {
                    applicationDeliveries.incrementAndGet();
                }
                @Override public void onRTCChannelError(NostrRTCChannel channel, Throwable error) {}
                @Override public void onRTCChannelClosed(NostrRTCChannel channel) {}
                @Override public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
            });
            receiver.addListener(new NostrTURNChannelListener() {
                @Override public void onTurnChannelReady(NostrTURNChannel channel) {}
                @Override public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {
                    receivedPacketIds.add(NostrRTCChannel.tryExtractPacketId(payload));
                    byte[] bytes = new byte[payload.remaining()];
                    payload.duplicate().get(bytes);
                    receivedFrames.add(bytes);
                    bobLogical.onTURNSocketMessage(payload);
                }
                @Override public void onTurnChannelError(NostrTURNChannel channel, Throwable error) {}
                @Override public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {}
            });
        }

        private void send(ByteBuffer payload) throws Exception {
            room.send(aliceLogical, payload).then(done -> {
                successes.incrementAndGet();
                completed.countDown();
                return null;
            }).catchException(error -> failures.incrementAndGet());
            queue = pendingSends(room).get(aliceLogical);
        }

        private int pendingReceipts() throws Exception {
            return ((AcknowledgedDeliveryTracker) field(sender, "deliveryTracker")).size();
        }

        @Override public void close() {
            room.close();
            out.connected.set(false);
            back.connected.set(false);
            sender.close("test-cleanup");
            receiver.close("test-cleanup");
            bobSocket.close();
            aliceExecutor.close();
            bobExecutor.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<NostrRTCPeer, NostrRTCSocket> connections(NostrRTCRoom room) throws Exception {
        return (Map<NostrRTCPeer, NostrRTCSocket>) field(room, "connections");
    }

    @SuppressWarnings("unchecked")
    private static Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>> pendingSends(NostrRTCRoom room) throws Exception {
        return (Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>>) field(room, "pendingSends");
    }

    private static Object queueHead(BlockingPacketQueue<?> queue) throws Exception {
        return ((java.util.Queue<?>) field(queue, "queue")).peek();
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static long longField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @FunctionalInterface
    private interface Check { boolean ok() throws Exception; }

    private static void waitUntil(Check check, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("condition did not complete within " + timeoutMs + " ms");
    }

    private static NostrRTCLocalPeer localPeer(String sessionId, NostrKeyPair roomKeyPair) {
        return new NostrRTCLocalPeer(
            RTCSettings.getDefault(APPLICATION_ID, PROTOCOL_ID).withStunServers(Collections.emptyList()),
            NostrKeyPairSigner.generate(),
            sessionId,
            roomKeyPair,
            "ws://linked.test/turn"
        );
    }

    private static NostrRTCPeer remotePeer(NostrRTCLocalPeer localPeer, NostrKeyPair roomKeyPair) {
        return new NostrRTCPeer(
            localPeer.getPubkey(),
            APPLICATION_ID,
            PROTOCOL_ID,
            localPeer.getSessionId(),
            roomKeyPair.getPublicKey(),
            localPeer.getTurnServer()
        );
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setLongField(Object target, String name, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static final class LinkedWebsocketTransport implements WebsocketTransport {

        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final AtomicInteger deliveryAcksToDrop = new AtomicInteger();
        private final AtomicInteger dataFrames = new AtomicInteger();
        private final AtomicInteger deliveryAckFrames = new AtomicInteger();
        private volatile NostrTURNChannel target;
        private final AtomicBoolean blockData = new AtomicBoolean();
        private volatile ByteBuffer lastDataFrame;
        private volatile ByteBuffer lastAckFrame;
        private final AtomicBoolean holdDeliveryAcks = new AtomicBoolean();
        private final List<ByteBuffer> heldAcks = new CopyOnWriteArrayList<ByteBuffer>();
        private final List<Integer> messageIds = new CopyOnWriteArrayList<Integer>();
        private volatile Runnable dataObserver;

        private void releaseAck(int index) {
            target.onBinaryMessage(heldAcks.get(index).asReadOnlyBuffer());
        }

        @Override
        public AsyncTask<Void> close(String reason) {
            connected.set(false);
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            connected.set(true);
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> send(String message) {
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> sendBinary(ByteBuffer payload) {
            ByteBuffer frame = copy(payload);
            String type = frameType(frame);
            if ("data".equals(type)) {
                lastDataFrame = frame;
                dataFrames.incrementAndGet();
                messageIds.add(Integer.valueOf(NostrTURNCodec.extractMessageId(frame)));
                Runnable observer = dataObserver;
                if (observer != null) observer.run();
                if (blockData.get()) return AsyncTask.completed(null);
            } else if ("delivery_ack".equals(type)) {
                lastAckFrame = frame;
                deliveryAckFrames.incrementAndGet();
                if (holdDeliveryAcks.get()) {
                    heldAcks.add(frame);
                    return AsyncTask.completed(null);
                }
                if (deliveryAcksToDrop.getAndUpdate(current -> Math.max(0, current - 1)) > 0) {
                    return AsyncTask.completed(null);
                }
            }
            NostrTURNChannel destination = target;
            if (destination == null) {
                return AsyncTask.failed(new IllegalStateException("Linked TURN target is missing"));
            }
            destination.onBinaryMessage(frame);
            return AsyncTask.completed(null);
        }

        @Override
        public void addListener(WebsocketTransportListener listener) {}

        @Override
        public void removeListener(WebsocketTransportListener listener) {}

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public void setMaxMessageSize(int maxMessageSize) {}

        @Override
        public int getMaxMessageSize() {
            return 10 * 1024 * 1024;
        }

        private static String frameType(ByteBuffer frame) {
            SignedNostrEvent header = NostrTURNCodec.decodeHeader(frame.asReadOnlyBuffer());
            return header.getFirstTagFirstValue("t");
        }

        private static ByteBuffer copy(ByteBuffer payload) {
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate());
            copy.flip();
            return copy.asReadOnlyBuffer();
        }
    }
}
