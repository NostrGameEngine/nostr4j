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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrTURNPool.TURNTransport;
import org.ngengine.nostr4j.rtc.delivery.AcknowledgedDeliveryTracker;
import org.ngengine.nostr4j.rtc.delivery.DeliveryFailures;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.RTCDataChannel;
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
            assertFalse(
                "each retry needs a fresh transport message",
                fixture.out.messageIds.get(0).equals(fixture.out.messageIds.get(1))
            );
            assertEquals(
                "exactly one pause/retry epoch, no premature watchdog restart",
                originalEpoch + 1L,
                longField(fixture.queue, "epoch")
            );
            assertEquals(
                "actual retry must use the original prepared packet",
                originalPacket,
                fixture.sentPreparedPackets.get(0)
            );
            assertEquals(
                "actual retry must use the original prepared packet",
                originalPacket,
                fixture.sentPreparedPackets.get(1)
            );
            assertEquals(
                "actual retry must preserve the original lifetime",
                Long.valueOf(originalTime),
                fixture.sentEnqueuedTimes.get(0)
            );
            assertEquals(
                "actual retry must preserve the original lifetime",
                Long.valueOf(originalTime),
                fixture.sentEnqueuedTimes.get(1)
            );
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

    @Test(timeout = 10_000L)
    public void ownedReceiveCloseRetriesPendingSendSiblingWithoutOvertakingOrForgingReceipt() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get, true, false)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 4 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            fixture.send(ByteBuffer.wrap(new byte[] { 5 }));
            Object originalEntry = queueHead(fixture.queue);
            Object originalPacket = field(originalEntry, "packet");
            long originalTime = longField(originalEntry, "enqueuedAtMs");
            long generation = fixture.aliceLogical.getWriteGeneration();
            NostrTURNChannel detachedSender = fixture.sender;
            assertFalse("fixture must exercise distinct TURN handles", fixture.companion == detachedSender);
            clock.addAndGet(20_000L);
            // This invokes the actual listener installed by ensureTurn -> disposeTurnIfOwned -> detachTurn.
            fixture.companion.close("remote-native-upgrade");
            waitUntil(() -> field(fixture.queue, "executionQueue") == null, 3000L);
            assertEquals("live logical endpoint must not fail its queued head", 0, fixture.failures.get());
            assertEquals(generation, fixture.aliceLogical.getWriteGeneration());
            assertTrue(detachedSender.isClosed());
            assertEquals(0, fixture.pendingReceipts());
            assertEquals(originalEntry, queueHead(fixture.queue));
            assertEquals(2, fixture.queue.size());
            detachedSender.onBinaryMessage(fixture.back.heldAcks.get(0).asReadOnlyBuffer());
            fixture.reconnectOwnedPair();
            fixture.queue.loop();
            waitUntil(() -> fixture.back.heldAcks.size() == 2, 3000L);
            assertEquals(originalEntry, queueHead(fixture.queue));
            assertEquals(originalPacket, fixture.sentPreparedPackets.get(1));
            assertEquals(Long.valueOf(originalTime), fixture.sentEnqueuedTimes.get(1));
            assertArrayEquals(fixture.receivedFrames.get(0), fixture.receivedFrames.get(1));
            assertEquals("successor cannot overtake pending head", 2, fixture.out.dataFrames.get());
            fixture.back.releaseAck(0); // Authenticated, but belongs to the detached handle/attempt.
            assertFalse(
                "stale detached receipt cannot complete replacement",
                fixture.completed.await(150L, TimeUnit.MILLISECONDS)
            );
            assertEquals(2, fixture.queue.size());
            fixture.back.releaseAck(1);
            waitUntil(() -> fixture.successes.get() == 1 && fixture.back.heldAcks.size() == 3, 3000L);
            assertEquals(1, fixture.queue.size());
            assertFalse(originalPacket == fixture.sentPreparedPackets.get(2));
            fixture.back.releaseAck(2);
            waitUntil(() -> fixture.successes.get() == 2, 3000L);
            assertEquals(0, fixture.failures.get());
            assertEquals(2, fixture.applicationDeliveries.get());
            assertEquals(0, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    @Test(timeout = 10_000L)
    public void ownedReceiveReplacementKeepsOriginalFiniteDeadlineWhenNoTransportReturns() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get, true, false)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 6 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            Object originalEntry = queueHead(fixture.queue);
            clock.addAndGet(20_000L);
            fixture.companion.close("remote-native-upgrade");
            waitUntil(() -> field(fixture.queue, "executionQueue") == null, 3000L);
            assertEquals(0, fixture.failures.get());
            assertEquals(originalEntry, queueHead(fixture.queue));
            clock.addAndGet(9_999L);
            fixture.queue.loop();
            assertEquals(0, fixture.failures.get());
            assertEquals(originalEntry, queueHead(fixture.queue));
            clock.incrementAndGet();
            fixture.queue.loop();
            waitUntil(() -> fixture.failures.get() == 1, 3000L);
            assertEquals(0, fixture.successes.get());
            assertEquals(0, fixture.queue.size());
            fixture.back.releaseAck(0);
            assertEquals(1, fixture.out.dataFrames.get());
        }
    }

    @Test(timeout = 10_000L)
    public void sharedTurnDetachClosesOnceAndPreservesPendingQueue() throws Exception {
        try (RoomTurnFixture fixture = new RoomTurnFixture(System::currentTimeMillis, true, true)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 7 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            assertTrue(fixture.sender == fixture.companion);
            AtomicInteger closes = new AtomicInteger();
            fixture.sender.addListener(
                new NostrTURNChannelListener() {
                    @Override
                    public void onTurnChannelReady(NostrTURNChannel channel) {}

                    @Override
                    public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {}

                    @Override
                    public void onTurnChannelError(NostrTURNChannel channel, Throwable error) {}

                    @Override
                    public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {
                        closes.incrementAndGet();
                    }
                }
            );
            detachOwnedTurns(fixture).run();
            waitUntil(() -> field(fixture.queue, "executionQueue") == null, 3000L);
            assertEquals(1, closes.get());
            assertEquals(0, fixture.failures.get());
            assertEquals(1, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    @Test(timeout = 15_000L)
    public void fullOwnerCloseAfterDetachAndDistinctHandleClosuresRemainTerminal() throws Exception {
        for (int closeKind = 0; closeKind < 5; closeKind++) {
            AtomicLong clock = new AtomicLong(1_000_000L);
            try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get, true, false)) {
                fixture.back.holdDeliveryAcks.set(true);
                fixture.send(ByteBuffer.wrap(new byte[] { 8 }));
                waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
                AtomicReference<Throwable> transportFailure = new AtomicReference<Throwable>();
                fixture.sender.write(ByteBuffer.wrap(new byte[] { 10 })).catchException(transportFailure::set);
                waitUntil(() -> fixture.back.heldAcks.size() == 2, 3000L);
                Runnable capturedCleanup = closeKind == 0 ? detachOwnedTurns(fixture) : null;
                if (closeKind == 0 || closeKind == 1) fixture.aliceLogical.close(); else if (
                    closeKind == 2
                ) fixture.aliceSocket.close(); else if (closeKind == 3) fixture.turnPool.close(); else fixture.room.close();
                if (capturedCleanup != null) capturedCleanup.run();
                waitUntil(() -> transportFailure.get() != null, 3000L);
                assertTrue(transportFailure.get() instanceof IllegalStateException);
                assertFalse(
                    "full owner close must reject the transport write hard",
                    DeliveryFailures.isRetryable(transportFailure.get())
                );
                // A closed logical channel invalidates the existing in-flight generation; its queue still has finite TTL.
                clock.addAndGet(30_000L);
                fixture.queue.loop();
                waitUntil(() -> fixture.failures.get() == 1, 3000L);
                assertFalse(DeliveryFailures.isRetryable(fixture.lastFailure.get()));
                assertEquals(0, fixture.successes.get());
                assertEquals(0, fixture.queue.size());
                assertTrue(fixture.sender.isClosed());
                assertTrue(fixture.companion.isClosed());
                fixture.back.releaseAck(0);
                fixture.back.releaseAck(1);
                fixture.queue.loop();
                assertEquals(1, fixture.failures.get());
                assertEquals(2, fixture.out.dataFrames.get());
            }
        }
    }

    @Test(timeout = 10_000L)
    public void poolCloseRejectsPendingSenderHardEvenWhenReceiveHandleClosesFirst() throws Exception {
        for (boolean receiveFirst : new boolean[] { false, true }) {
            AtomicLong clock = new AtomicLong(1_000_000L);
            try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get, true, false)) {
                fixture.back.holdDeliveryAcks.set(true);
                fixture.send(ByteBuffer.wrap(new byte[] { 12 }));
                waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
                if (receiveFirst) {
                    @SuppressWarnings("unchecked")
                    List<NostrTURNChannel> channels = (List<NostrTURNChannel>) field(fixture.turnPool, "channels");
                    channels.remove(fixture.companion);
                    channels.add(0, fixture.companion);
                }
                fixture.turnPool.close();
                waitUntil(() -> fixture.failures.get() == 1, 3000L);
                assertEquals("terminal pool failure must not wait for or renew queue TTL", 1_000_000L, clock.get());
                assertFalse(DeliveryFailures.isRetryable(fixture.lastFailure.get()));
                assertEquals(0, fixture.successes.get());
                assertEquals(0, fixture.queue.size());
                assertEquals(0, fixture.pendingReceipts());
                assertTrue(fixture.sender.isClosed());
                assertTrue(fixture.companion.isClosed());
                fixture.back.releaseAck(0);
                fixture.queue.loop();
                assertEquals(1, fixture.failures.get());
            }
        }
    }

    @Test(timeout = 10_000L)
    public void localNativeInstallKeepsExistingGenerationFenceAndCrossTransportDedup() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get, true, false)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 11 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1 && fixture.applicationDeliveries.get() == 1, 3000L);
            long generation = fixture.aliceLogical.getWriteGeneration();
            NostrTURNChannel oldSender = fixture.sender;
            MemoryRTCDataChannel nativeChannel = new MemoryRTCDataChannel(fixture.bobLogical);
            clock.addAndGet(20_000L);
            fixture.aliceSocket.setForceTURN(false);
            fixture.aliceLogical.setChannel(nativeChannel);
            assertEquals(generation + 1L, fixture.aliceLogical.getWriteGeneration());
            fixture.queue.loop();
            waitUntil(() -> fixture.successes.get() == 1, 3000L);
            assertEquals(1, nativeChannel.frames.size());
            assertArrayEquals(fixture.receivedFrames.get(0), nativeChannel.frames.get(0));
            assertEquals(
                "cross-transport retry must not duplicate application delivery",
                1,
                fixture.applicationDeliveries.get()
            );
            assertTrue(oldSender.isClosed());
            fixture.back.releaseAck(0);
            fixture.queue.loop();
            assertEquals(1, fixture.successes.get());
            assertEquals(0, fixture.failures.get());
            assertEquals(0, fixture.queue.size());
        }
    }

    @Test(timeout = 10_000L)
    public void genericIllegalStateFromLiveTurnSendRemainsTerminal() throws Exception {
        try (RoomTurnFixture fixture = new RoomTurnFixture(System::currentTimeMillis, true, false)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 9 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            fixture.sender.onError(new IllegalStateException("controlled-hard-failure"));
            waitUntil(() -> fixture.failures.get() == 1, 3000L);
            assertFalse(DeliveryFailures.isRetryable(fixture.lastFailure.get()));
            fixture.queue.restart();
            assertEquals(0, fixture.queue.size());
            assertEquals(1, fixture.out.dataFrames.get());
            assertEquals(0, fixture.successes.get());
        }
    }

    @Test(timeout = 10_000L)
    public void turnOnlyPhysicalRetirementPreservesPendingHeadAndAuthenticatedReplacementOrder() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get, true, false)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 13 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1 && fixture.applicationDeliveries.get() == 1, 3000L);
            fixture.send(ByteBuffer.wrap(new byte[] { 14 }));
            Object originalEntry = queueHead(fixture.queue);
            Object originalPacket = field(originalEntry, "packet");
            long originalTime = longField(originalEntry, "enqueuedAtMs");
            long generation = fixture.aliceLogical.getWriteGeneration();
            NostrTURNChannel oldSender = fixture.sender;
            assertEquals(null, field(fixture.aliceLogical, "channel"));
            clock.addAndGet(20_000L);
            PhysicalResourceRetirement retirement = fixture.aliceSocket.capturePhysicalRetirement(ignored -> {});
            retirement.start();
            waitUntil(retirement::isSettled, 3000L);
            fixture.queue.loop();
            waitUntil(() -> field(fixture.queue, "executionQueue") == null, 3000L);
            assertEquals("physical replacement must not reject a live logical caller", 0, fixture.failures.get());
            assertEquals(originalEntry, queueHead(fixture.queue));
            assertEquals(2, fixture.queue.size());
            assertTrue(retirement.isSuccessful());
            assertFalse(fixture.aliceSocket.isClosed());
            assertFalse(fixture.aliceLogical.isClosed());
            assertTrue(
                "captured TURN attempt must lose its write generation",
                generation != fixture.aliceLogical.getWriteGeneration()
            );
            assertTrue(oldSender.isClosed());
            assertEquals(0, fixture.pendingReceipts());
            fixture.aliceSocket.setPhysicalLinkEnabled(true);
            setField(fixture.aliceSocket, "turnFallbackAllowed", Boolean.TRUE);
            fixture.reconnectOwnedPair();
            fixture.queue.loop();
            waitUntil(() -> fixture.back.heldAcks.size() == 2, 3000L);
            assertEquals(originalEntry, queueHead(fixture.queue));
            assertEquals(originalPacket, fixture.sentPreparedPackets.get(1));
            assertEquals(Long.valueOf(originalTime), fixture.sentEnqueuedTimes.get(1));
            assertArrayEquals(fixture.receivedFrames.get(0), fixture.receivedFrames.get(1));
            assertEquals("successor must remain behind unacknowledged original head", 2, fixture.out.dataFrames.get());
            oldSender.onBinaryMessage(fixture.back.heldAcks.get(0).asReadOnlyBuffer());
            fixture.back.releaseAck(0);
            assertFalse("retired receipt cannot acknowledge replacement", fixture.completed.await(150L, TimeUnit.MILLISECONDS));
            fixture.back.releaseAck(1);
            waitUntil(() -> fixture.successes.get() == 1 && fixture.back.heldAcks.size() == 3, 3000L);
            assertEquals(1, fixture.queue.size());
            fixture.back.releaseAck(2);
            waitUntil(() -> fixture.successes.get() == 2, 3000L);
            assertEquals(2, fixture.applicationDeliveries.get());
            assertEquals(0, fixture.failures.get());
            assertEquals(0, fixture.queue.size());
            assertEquals(0, fixture.pendingReceipts());
        }
    }

    @Test(timeout = 10_000L)
    public void turnOnlyPhysicalRetirementCannotRenewOriginalQueueDeadline() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        try (RoomTurnFixture fixture = new RoomTurnFixture(clock::get, true, false)) {
            fixture.back.holdDeliveryAcks.set(true);
            fixture.send(ByteBuffer.wrap(new byte[] { 15 }));
            waitUntil(() -> fixture.back.heldAcks.size() == 1, 3000L);
            Object originalEntry = queueHead(fixture.queue);
            assertEquals(null, field(fixture.aliceLogical, "channel"));
            clock.addAndGet(20_000L);
            PhysicalResourceRetirement retirement = fixture.aliceSocket.capturePhysicalRetirement(ignored -> {});
            retirement.start();
            waitUntil(retirement::isSettled, 3000L);
            fixture.queue.loop();
            waitUntil(() -> field(fixture.queue, "executionQueue") == null, 3000L);
            assertEquals(0, fixture.failures.get());
            assertEquals(originalEntry, queueHead(fixture.queue));
            clock.addAndGet(9_999L);
            fixture.queue.loop();
            assertEquals(0, fixture.failures.get());
            assertEquals(originalEntry, queueHead(fixture.queue));
            clock.incrementAndGet();
            fixture.queue.loop();
            waitUntil(() -> fixture.failures.get() == 1, 3000L);
            fixture.back.releaseAck(0);
            fixture.queue.loop();
            assertEquals(0, fixture.successes.get());
            assertEquals(1, fixture.out.dataFrames.get());
            assertEquals(0, fixture.queue.size());
        }
    }

    private static Runnable detachOwnedTurns(RoomTurnFixture fixture) throws Exception {
        java.lang.reflect.Method detach = NostrRTCChannel.class.getDeclaredMethod("detachTurn");
        detach.setAccessible(true);
        synchronized (fixture.aliceSocket) {
            return (Runnable) detach.invoke(fixture.aliceLogical);
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
        private final AtomicReference<Throwable> lastFailure = new AtomicReference<Throwable>();
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
        private NostrTURNChannel sender;
        private NostrTURNChannel receiver;
        private NostrTURNChannel companion;
        private final NostrTURNPool turnPool;
        private final NostrKeyPair keys;
        private final NostrRTCLocalPeer alice;
        private final NostrRTCLocalPeer bob;
        private final List<NostrTURNChannel> retiredReceivers = new CopyOnWriteArrayList<NostrTURNChannel>();
        private BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue;

        private RoomTurnFixture(LongSupplier clock) throws Exception {
            this(clock, false, false);
        }

        private RoomTurnFixture(LongSupplier clock, boolean ownedTurns, boolean sharedTurns) throws Exception {
            keys = new NostrKeyPair();
            alice =
                localPeer(
                    "room-alice",
                    keys,
                    ownedTurns && !sharedTurns ? "ws://linked.test/receive" : "ws://linked.test/turn"
                );
            bob = localPeer("room-bob", keys, ownedTurns && !sharedTurns ? "ws://linked.test/send" : "ws://linked.test/turn");
            turnPool = ownedTurns ? newPausedTurnPool() : null;
            if (turnPool != null) {
                out.dropControl.set(true);
                back.dropControl.set(true);
                turnTransports(turnPool).put(bob.getTurnServer(), AsyncTask.completed(new TURNTransport(out)));
                if (!sharedTurns) {
                    LinkedWebsocketTransport inbound = new LinkedWebsocketTransport();
                    inbound.dropControl.set(true);
                    turnTransports(turnPool).put(alice.getTurnServer(), AsyncTask.completed(new TURNTransport(inbound)));
                }
            }
            RTCSettings settings = RTCSettings
                .getDefault(APPLICATION_ID, PROTOCOL_ID)
                .withSignalingRelays(Collections.emptyList())
                .withStunServers(Collections.emptyList());
            room = new NostrRTCRoom(settings, alice, keys, new NostrPool(), null, clock);
            aliceSocket = new NostrRTCSocket(aliceExecutor, remotePeer(bob, keys), keys, alice, settings, turnPool);
            bobSocket = new NostrRTCSocket(bobExecutor, remotePeer(alice, keys), keys, bob, settings, null);
            aliceSocket.setForceTURN(true);
            if (ownedTurns) setField(aliceSocket, "turnFallbackAllowed", Boolean.TRUE);
            aliceLogical = aliceSocket.createChannel(CHANNEL);
            bobLogical = bobSocket.createChannel(CHANNEL);
            if (ownedTurns) {
                aliceLogical.activateFallbackIfNeeded();
                sender = (NostrTURNChannel) field(aliceLogical, "turnSend");
                companion = (NostrTURNChannel) field(aliceLogical, "turnReceive");
                waitUntil(() -> field(sender, "transport") != null && field(companion, "transport") != null, 3000L);
                setIntField(companion, "state", 2);
            } else {
                sender = new NostrTURNChannel(alice, remotePeer(bob, keys), "ws://linked.test/turn", keys, CHANNEL, true, 32);
                companion = sender;
                sender.setTransport(new TURNTransport(out));
                setField(aliceLogical, "turnSend", sender);
                setField(aliceLogical, "turnReceive", sender);
            }
            receiver = new NostrTURNChannel(bob, remotePeer(alice, keys), "ws://linked.test/turn", keys, CHANNEL, true, 32);
            setLongField(receiver, "vSocketId", sender.getRoutingVsocketId());
            out.target = receiver;
            back.target = sender;
            receiver.setTransport(new TURNTransport(back));
            setIntField(sender, "state", 2);
            setIntField(receiver, "state", 2);
            connections(room).put(aliceSocket.getRemotePeer(), aliceSocket);
            out.dataObserver =
                () -> {
                    try {
                        Object entry = queueHead(pendingSends(room).get(aliceLogical));
                        sentPreparedPackets.add(field(entry, "packet"));
                        sentEnqueuedTimes.add(Long.valueOf(longField(entry, "enqueuedAtMs")));
                    } catch (Exception error) {
                        throw new IllegalStateException("could not observe production queue entry", error);
                    }
                };
            bobLogical.addListener(
                new NostrRTCChannelListener() {
                    @Override
                    public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer payload, boolean turn) {
                        applicationDeliveries.incrementAndGet();
                    }

                    @Override
                    public void onRTCChannelError(NostrRTCChannel channel, Throwable error) {}

                    @Override
                    public void onRTCChannelClosed(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
                }
            );
            attachReceiverListener();
        }

        private void attachReceiverListener() {
            receiver.addListener(
                new NostrTURNChannelListener() {
                    @Override
                    public void onTurnChannelReady(NostrTURNChannel channel) {}

                    @Override
                    public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {
                        receivedPacketIds.add(NostrRTCChannel.tryExtractPacketId(payload));
                        byte[] bytes = new byte[payload.remaining()];
                        payload.duplicate().get(bytes);
                        receivedFrames.add(bytes);
                        bobLogical.onTURNSocketMessage(payload);
                    }

                    @Override
                    public void onTurnChannelError(NostrTURNChannel channel, Throwable error) {}

                    @Override
                    public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {}
                }
            );
        }

        private void reconnectOwnedPair() throws Exception {
            retiredReceivers.add(receiver);
            aliceLogical.activateFallbackIfNeeded();
            sender = (NostrTURNChannel) field(aliceLogical, "turnSend");
            companion = (NostrTURNChannel) field(aliceLogical, "turnReceive");
            waitUntil(() -> field(sender, "transport") != null && field(companion, "transport") != null, 3000L);
            receiver = new NostrTURNChannel(bob, remotePeer(alice, keys), bob.getTurnServer(), keys, CHANNEL, true, 32);
            setLongField(receiver, "vSocketId", sender.getRoutingVsocketId());
            out.target = receiver;
            back.target = sender;
            receiver.setTransport(new TURNTransport(back));
            setIntField(sender, "state", 2);
            setIntField(companion, "state", 2);
            setIntField(receiver, "state", 2);
            attachReceiverListener();
        }

        private void send(ByteBuffer payload) throws Exception {
            room
                .send(aliceLogical, payload)
                .then(done -> {
                    successes.incrementAndGet();
                    completed.countDown();
                    return null;
                })
                .catchException(error -> {
                    lastFailure.set(error);
                    failures.incrementAndGet();
                });
            queue = pendingSends(room).get(aliceLogical);
        }

        private int pendingReceipts() throws Exception {
            return ((AcknowledgedDeliveryTracker) field(sender, "deliveryTracker")).size();
        }

        @Override
        public void close() {
            room.close();
            out.connected.set(false);
            back.connected.set(false);
            sender.close("test-cleanup");
            receiver.close("test-cleanup");
            for (NostrTURNChannel retired : retiredReceivers) retired.close("test-cleanup");
            if (turnPool != null) turnPool.close();
            bobSocket.close();
            aliceExecutor.close();
            bobExecutor.close();
        }
    }

    /**
     * Use the real pool/connect/listeners without scheduled network maintenance in this in-memory fixture.
     */
    private static NostrTURNPool newPausedTurnPool() throws Exception {
        Field platformField = NGEPlatform.class.getDeclaredField("platform");
        platformField.setAccessible(true);
        Object previous = platformField.get(null);
        try {
            platformField.set(
                null,
                new JVMAsyncPlatform() {
                    @Override
                    public AsyncExecutor newAsyncExecutor(Object key) {
                        if (key != NostrTURNPool.class) return super.newAsyncExecutor(key);
                        return new AsyncExecutor() {
                            @Override
                            public <T> AsyncTask<T> runLater(Callable<T> callback, long delay, TimeUnit unit) {
                                return AsyncTask.create((resolve, reject) -> {});
                            }

                            @Override
                            public <T> AsyncTask<T> run(Callable<T> callback) {
                                try {
                                    return AsyncTask.completed(callback.call());
                                } catch (Exception error) {
                                    return AsyncTask.failed(error);
                                }
                            }

                            @Override
                            public void close() {}
                        };
                    }
                }
            );
            return new NostrTURNPool();
        } finally {
            platformField.set(null, previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AsyncTask<TURNTransport>> turnTransports(NostrTURNPool pool) throws Exception {
        return (Map<String, AsyncTask<TURNTransport>>) field(pool, "transports");
    }

    @SuppressWarnings("unchecked")
    private static Map<NostrRTCPeer, NostrRTCSocket> connections(NostrRTCRoom room) throws Exception {
        return (Map<NostrRTCPeer, NostrRTCSocket>) field(room, "connections");
    }

    @SuppressWarnings("unchecked")
    private static Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>> pendingSends(NostrRTCRoom room)
        throws Exception {
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
    private interface Check {
        boolean ok() throws Exception;
    }

    private static void waitUntil(Check check, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("condition did not complete within " + timeoutMs + " ms");
    }

    private static NostrRTCLocalPeer localPeer(String sessionId, NostrKeyPair roomKeyPair) {
        return localPeer(sessionId, roomKeyPair, "ws://linked.test/turn");
    }

    private static NostrRTCLocalPeer localPeer(String sessionId, NostrKeyPair roomKeyPair, String turnUrl) {
        return new NostrRTCLocalPeer(
            RTCSettings.getDefault(APPLICATION_ID, PROTOCOL_ID).withStunServers(Collections.emptyList()),
            NostrKeyPairSigner.generate(),
            sessionId,
            roomKeyPair,
            turnUrl
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

    private static final class MemoryRTCDataChannel extends RTCDataChannel {

        private final NostrRTCChannel receiver;
        private final List<byte[]> frames = new CopyOnWriteArrayList<byte[]>();

        private MemoryRTCDataChannel(NostrRTCChannel receiver) {
            super(CHANNEL, PROTOCOL_ID, true, true, 0, null);
            this.receiver = receiver;
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.duplicate().get(bytes);
            frames.add(bytes);
            receiver.onRTCSocketMessage(ByteBuffer.wrap(bytes));
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Number> getMaxMessageSize() {
            return AsyncTask.completed(Integer.valueOf(262_144));
        }

        @Override
        public AsyncTask<Number> getAvailableAmount() {
            return AsyncTask.completed(Integer.valueOf(262_144));
        }

        @Override
        public AsyncTask<Number> getBufferedAmount() {
            return AsyncTask.completed(Integer.valueOf(0));
        }

        @Override
        public AsyncTask<Void> setBufferedAmountLowThreshold(int threshold) {
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> close() {
            return AsyncTask.completed(null);
        }
    }

    private static final class LinkedWebsocketTransport implements WebsocketTransport {

        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final AtomicInteger deliveryAcksToDrop = new AtomicInteger();
        private final AtomicInteger dataFrames = new AtomicInteger();
        private final AtomicInteger deliveryAckFrames = new AtomicInteger();
        private volatile NostrTURNChannel target;
        private final AtomicBoolean blockData = new AtomicBoolean();
        private final AtomicBoolean dropControl = new AtomicBoolean();
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
            if (dropControl.get() && !"data".equals(type) && !"delivery_ack".equals(type)) return AsyncTask.completed(null);
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
