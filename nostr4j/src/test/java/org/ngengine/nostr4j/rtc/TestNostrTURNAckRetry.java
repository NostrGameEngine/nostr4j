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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.NostrTURNPool.TURNTransport;
import org.ngengine.nostr4j.rtc.delivery.DeliveryAckTimeoutException;
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
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class TestNostrTURNAckRetry {

    private static final Logger logger = Logger.getLogger(TestNostrTURNAckRetry.class.getName());
    private static final String APPLICATION_ID = "turn-ack-retry-app";
    private static final String PROTOCOL_ID = "turn-ack-retry-proto";
    private static final String CHANNEL = "primary";

    @Test(timeout = 25_000L)
    public void testLostDeliveryAckRetriesSamePacketAndRegeneratesAckWithoutDuplicateDelivery() throws Exception {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrRTCLocalPeer alice = localPeer("alice-session", roomKeyPair);
        NostrRTCLocalPeer bob = localPeer("bob-session", roomKeyPair);
        NostrRTCPeer aliceRemote = remotePeer(alice, roomKeyPair);
        NostrRTCPeer bobRemote = remotePeer(bob, roomKeyPair);
        AsyncExecutor aliceExecutor = NGEPlatform.get().newAsyncExecutor("turn-ack-retry-alice");
        AsyncExecutor bobExecutor = NGEPlatform.get().newAsyncExecutor("turn-ack-retry-bob");
        NostrRTCSocket aliceSocket = new NostrRTCSocket(
            aliceExecutor,
            bobRemote,
            roomKeyPair,
            alice,
            RTCSettings.DEFAULT,
            null,
            null
        );
        NostrRTCSocket bobSocket = new NostrRTCSocket(
            bobExecutor,
            aliceRemote,
            roomKeyPair,
            bob,
            RTCSettings.DEFAULT,
            null,
            null
        );
        NostrRTCChannel aliceLogical = new NostrRTCChannel(CHANNEL, aliceSocket, true, true, Integer.valueOf(0), null);
        NostrRTCChannel bobLogical = new NostrRTCChannel(CHANNEL, bobSocket, true, true, Integer.valueOf(0), null);
        NostrTURNChannel aliceTurn = new NostrTURNChannel(
            alice,
            bobRemote,
            "ws://linked.test/turn",
            roomKeyPair,
            CHANNEL,
            true,
            32
        );
        NostrTURNChannel bobTurn = new NostrTURNChannel(
            bob,
            aliceRemote,
            "ws://linked.test/turn",
            roomKeyPair,
            CHANNEL,
            true,
            32
        );
        LinkedWebsocketTransport aliceTransport = new LinkedWebsocketTransport();
        LinkedWebsocketTransport bobTransport = new LinkedWebsocketTransport();
        BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue = null;

        try {
            setLongField(bobTurn, "vSocketId", aliceTurn.getRoutingVsocketId());
            aliceTransport.target = bobTurn;
            bobTransport.target = aliceTurn;
            bobTransport.deliveryAcksToDrop.set(1);
            aliceTurn.setTransport(new TURNTransport(aliceTransport));
            bobTurn.setTransport(new TURNTransport(bobTransport));
            setIntField(aliceTurn, "state", 2);
            setIntField(bobTurn, "state", 2);

            AtomicInteger applicationDeliveries = new AtomicInteger();
            CountDownLatch applicationDelivery = new CountDownLatch(1);
            bobLogical.addListener(
                new NostrRTCChannelListener() {
                    @Override
                    public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer payload, boolean turn) {
                        byte[] bytes = new byte[payload.remaining()];
                        payload.duplicate().get(bytes);
                        if ("lost-delivery-ack".equals(new String(bytes, StandardCharsets.UTF_8))) {
                            applicationDeliveries.incrementAndGet();
                            applicationDelivery.countDown();
                        }
                    }

                    @Override
                    public void onRTCChannelError(NostrRTCChannel channel, Throwable error) {}

                    @Override
                    public void onRTCChannelClosed(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
                }
            );
            bobTurn.addListener(
                new NostrTURNChannelListener() {
                    @Override
                    public void onTurnChannelReady(NostrTURNChannel channel) {}

                    @Override
                    public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {
                        bobLogical.onTURNSocketMessage(payload);
                    }

                    @Override
                    public void onTurnChannelError(NostrTURNChannel channel, Throwable error) {}

                    @Override
                    public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {}
                }
            );

            AtomicInteger attempts = new AtomicInteger();
            List<Long> packetIds = new CopyOnWriteArrayList<Long>();
            AtomicReference<Throwable> retryableFailure = new AtomicReference<Throwable>();
            AtomicReference<Throwable> terminalFailure = new AtomicReference<Throwable>();
            CountDownLatch completed = new CountDownLatch(1);
            queue =
                new BlockingPacketQueue<NostrRTCChannel.PreparedPacket>(
                    new BlockingPacketQueue.PacketHandler<NostrRTCChannel.PreparedPacket>() {
                        @Override
                        public AsyncTask<Boolean> handle(NostrRTCChannel.PreparedPacket packet) {
                            attempts.incrementAndGet();
                            packetIds.add(Long.valueOf(packet.packetId()));
                            return aliceTurn.write(frame(packet));
                        }

                        @Override
                        public boolean isReady() {
                            return aliceTurn.isReady();
                        }

                        @Override
                        public boolean shouldPauseOnError(Throwable error) {
                            retryableFailure.compareAndSet(null, error);
                            return DeliveryFailures.isRetryable(error);
                        }
                    },
                    logger,
                    "Failed to deliver TURN packet",
                    100L,
                    15_000L
                );

            NostrRTCChannel.PreparedPacket packet = aliceLogical.prepareOutgoingPacket(
                ByteBuffer.wrap("lost-delivery-ack".getBytes(StandardCharsets.UTF_8))
            );
            queue.enqueue(packet, ignored -> completed.countDown(), terminalFailure::set);

            assertTrue("receiver did not observe the logical packet", applicationDelivery.await(3, TimeUnit.SECONDS));
            assertTrue("sender did not complete after retrying the lost ACK", completed.await(18, TimeUnit.SECONDS));
            assertEquals("two TURN data attempts are required", 2, aliceTransport.dataFrames.get());
            assertEquals("two queue attempts are required", 2, attempts.get());
            assertEquals(2, packetIds.size());
            assertEquals(Long.valueOf(packet.packetId()), packetIds.get(0));
            assertEquals(Long.valueOf(packet.packetId()), packetIds.get(1));
            assertEquals("the duplicate must not reach the application", 1, applicationDeliveries.get());
            assertEquals("the receiver must generate a fresh ACK for both attempts", 2, bobTransport.deliveryAckFrames.get());
            assertTrue(
                "the first attempt must fail on delivery ACK timeout: " + retryableFailure.get(),
                hasCause(retryableFailure.get(), DeliveryAckTimeoutException.class)
            );
            assertNull("the queued send must ultimately succeed", terminalFailure.get());
        } finally {
            if (queue != null) {
                queue.close();
            }
            aliceTransport.connected.set(false);
            bobTransport.connected.set(false);
            aliceTurn.close("test-cleanup");
            bobTurn.close("test-cleanup");
            aliceSocket.close();
            bobSocket.close();
            aliceExecutor.close();
            bobExecutor.close();
        }
    }

    private static NostrRTCLocalPeer localPeer(String sessionId, NostrKeyPair roomKeyPair) {
        return new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            Collections.emptyList(),
            APPLICATION_ID,
            PROTOCOL_ID,
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

    private static ByteBuffer frame(NostrRTCChannel.PreparedPacket packet) {
        ByteBuffer payload = packet.payload();
        ByteBuffer frame = ByteBuffer.allocate(Long.BYTES + Short.BYTES + Short.BYTES + payload.remaining());
        frame.putLong(packet.packetId());
        frame.putShort((short) 0);
        frame.putShort((short) 1);
        frame.put(payload);
        frame.flip();
        return frame.asReadOnlyBuffer();
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
                dataFrames.incrementAndGet();
            } else if ("delivery_ack".equals(type)) {
                deliveryAckFrames.incrementAndGet();
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
