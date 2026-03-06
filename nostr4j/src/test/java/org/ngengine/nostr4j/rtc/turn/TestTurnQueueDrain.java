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

package org.ngengine.nostr4j.rtc.turn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNPool.TURNTransport;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNAckEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNCodec;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNDataEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class TestTurnQueueDrain {

    private static final String APP_ID = "queue-app";
    private static final String PROTOCOL_ID = "queue-proto";
    private static final String CHANNEL = "primary";

    @Test
    public void testOutgoingQueueDrainsAfterAck() throws Exception {
        NostrKeyPair room = new NostrKeyPair();
        NostrRTCLocalPeer alice = localPeer("alice-q-out", room);
        NostrRTCLocalPeer bob = localPeer("bob-q-out", room);
        NostrRTCPeer bobRemote = remotePeer(bob, room);
        NostrRTCPeer aliceRemote = remotePeer(alice, room);

        NostrTURNChannel channel = new NostrTURNChannel(alice, bobRemote, "ws://turn.test", room, CHANNEL, 24);
        long vsocketId = getVsocketId(channel);

        AsyncTask<Void> writeTask = channel.write(ByteBuffer.wrap("queued-turn-out".getBytes(StandardCharsets.UTF_8)));
        assertNull("Write should be queued while TURN channel is not ready", writeTask);
        assertEquals(1, queueSize(channel, "outgoingPayloadQueue"));

        RecordingWebsocketTransport ws = new RecordingWebsocketTransport();
        channel.setTransport(new TURNTransport(ws));
        setState(channel, 1);

        ByteBuffer ackFrame = NGEUtils.awaitNoThrow(
            NostrTURNAckEvent.createAck(alice, aliceRemote, room, CHANNEL, vsocketId).encodeToFrame(null)
        );
        channel.onBinaryMessage(ackFrame);

        awaitCondition(
            new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return ws.getSentBinaryFrames().size() >= 1;
                }
            },
            4000,
            "Queued TURN outgoing payload did not drain"
        );

        ByteBuffer sentFrame = ws.getSentBinaryFrames().get(0).duplicate();
        SignedNostrEvent header = NostrTURNCodec.decodeHeader(sentFrame);
        NostrTURNDataEvent incomingAtBob = NostrTURNDataEvent.parseIncoming(
            header,
            bob,
            aliceRemote,
            room,
            CHANNEL,
            NostrTURNCodec.extractVsocketId(sentFrame)
        );
        Collection<ByteBuffer> payloads = NGEUtils.awaitNoThrow(incomingAtBob.decodeFramePayloads(sentFrame));
        assertEquals(1, payloads.size());
        ByteBuffer only = payloads.iterator().next().duplicate();
        byte[] decoded = new byte[only.remaining()];
        only.get(decoded);
        assertEquals("queued-turn-out", new String(decoded, StandardCharsets.UTF_8));
        assertEquals(0, queueSize(channel, "outgoingPayloadQueue"));
    }

    @Test
    public void testIncomingQueueDrainsAfterAck() throws Exception {
        NostrKeyPair room = new NostrKeyPair();
        NostrRTCLocalPeer alice = localPeer("alice-q-in", room);
        NostrRTCLocalPeer bob = localPeer("bob-q-in", room);
        NostrRTCPeer bobRemote = remotePeer(bob, room);
        NostrRTCPeer aliceRemote = remotePeer(alice, room);

        NostrTURNChannel channel = new NostrTURNChannel(alice, bobRemote, "ws://turn.test", room, CHANNEL, 24);
        long vsocketId = getVsocketId(channel);
        setState(channel, 1);

        CountDownLatch payloadLatch = new CountDownLatch(1);
        List<String> received = new ArrayList<String>();
        channel.addListener(
            new NostrTURNChannelListener() {
                @Override
                public void onTurnChannelReady(NostrTURNChannel channel) {}

                @Override
                public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {}

                @Override
                public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {}

                @Override
                public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {
                    ByteBuffer copy = payload.duplicate();
                    byte[] bytes = new byte[copy.remaining()];
                    copy.get(bytes);
                    received.add(new String(bytes, StandardCharsets.UTF_8));
                    payloadLatch.countDown();
                }
            }
        );

        byte[] key = NGEUtils.getPlatform().randomBytes(32);
        ByteBuffer dataFrame = NGEUtils.awaitNoThrow(
            NostrTURNDataEvent
                .createOutgoing(bob, aliceRemote, room, CHANNEL, vsocketId, key)
                .encodeToFrame(List.of(ByteBuffer.wrap("queued-turn-in".getBytes(StandardCharsets.UTF_8))))
        );

        channel.onBinaryMessage(dataFrame);
        assertEquals(1, queueSize(channel, "incomingPayloadQueue"));

        ByteBuffer ackFrame = NGEUtils.awaitNoThrow(
            NostrTURNAckEvent.createAck(alice, aliceRemote, room, CHANNEL, vsocketId).encodeToFrame(null)
        );
        channel.onBinaryMessage(ackFrame);

        assertTrue("Queued TURN incoming payload did not drain", payloadLatch.await(4, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals("queued-turn-in", received.get(0));
        awaitCondition(
            new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return queueSize(channel, "incomingPayloadQueue") == 0;
                }
            },
            2000,
            "Incoming TURN queue did not drain to empty"
        );
    }

    private static NostrRTCLocalPeer localPeer(String sessionId, NostrKeyPair room) {
        return new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            sessionId,
            room,
            "ws://turn.test"
        );
    }

    private static NostrRTCPeer remotePeer(NostrRTCLocalPeer peer, NostrKeyPair room) {
        return new NostrRTCPeer(
            peer.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            peer.getSessionId(),
            room.getPublicKey(),
            "ws://turn.test"
        );
    }

    private static void setState(NostrTURNChannel channel, int state) {
        try {
            java.lang.reflect.Field stateField = NostrTURNChannel.class.getDeclaredField("state");
            stateField.setAccessible(true);
            stateField.setInt(channel, state);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to set TURN channel state", e);
        }
    }

    private static int queueSize(NostrTURNChannel channel, String fieldName) {
        try {
            java.lang.reflect.Field queueField = NostrTURNChannel.class.getDeclaredField(fieldName);
            queueField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Queue<ByteBuffer> queue = (Queue<ByteBuffer>) queueField.get(channel);
            return queue.size();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to inspect TURN queue " + fieldName, e);
        }
    }

    private static long getVsocketId(NostrTURNChannel channel) {
        try {
            java.lang.reflect.Field vsocketField = NostrTURNChannel.class.getDeclaredField("vSocketId");
            vsocketField.setAccessible(true);
            return vsocketField.getLong(channel);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to inspect TURN channel vsocketId", e);
        }
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMs, String error) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(error);
    }

    private static final class RecordingWebsocketTransport implements WebsocketTransport {

        private final List<WebsocketTransportListener> listeners = new ArrayList<WebsocketTransportListener>();
        private final List<ByteBuffer> sentBinaryFrames = new ArrayList<ByteBuffer>();
        private volatile boolean connected = true;

        @Override
        public AsyncTask<Void> close(String reason) {
            connected = false;
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            connected = true;
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> send(String message) {
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> sendBinary(ByteBuffer payload) {
            ByteBuffer copy = payload.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            sentBinaryFrames.add(ByteBuffer.wrap(bytes));
            return AsyncTask.completed(null);
        }

        @Override
        public void addListener(WebsocketTransportListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(WebsocketTransportListener listener) {
            listeners.remove(listener);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        private List<ByteBuffer> getSentBinaryFrames() {
            return sentBinaryFrames;
        }
    }
}
