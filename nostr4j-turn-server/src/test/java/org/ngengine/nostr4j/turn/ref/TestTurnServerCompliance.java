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

package org.ngengine.nostr4j.turn.ref;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNAckEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNChallengeEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNCodec;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNConnectEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNDataEvent;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNDisconnectEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestTurnServerCompliance {

    private static final String APP_ID = "app-turn-test";
    private static final String PROTOCOL_ID = "proto-turn-test";
    private static final String CHANNEL_LABEL = "default";

    private TurnServer server;
    private URI wsUri;
    private NostrKeyPair roomKeyPair;
    private NostrKeyPairSigner serverSigner;

    @Before
    public void setup() throws Exception {
        int port = freePort();
        this.roomKeyPair = new NostrKeyPair();
        this.serverSigner = NostrKeyPairSigner.generate();
        this.server = new TurnServer(port, serverSigner, 8, 10, 32, 3);
        this.server.start();
        this.wsUri = URI.create("ws://127.0.0.1:" + this.server.getPort() + "/turn");
    }

    @After
    public void teardown() throws Exception {
        if (this.server != null) {
            this.server.stop();
        }
    }

    @Test
    public void testAuthenticationRejectsConnectWithoutRoomProof() throws Exception {
        NostrKeyPairSigner attackerSigner = NostrKeyPairSigner.generate();
        NostrRTCLocalPeer attacker = localPeer(attackerSigner, roomKeyPair, "sess-attacker");
        NostrRTCPeer victimRemote = remotePeer(NostrKeyPairSigner.generate(), roomKeyPair, "sess-victim");

        WsClient ws = WsClient.connect(wsUri);
        SignedNostrEvent challengeHeader = waitForType(ws, "challenge", 2000);
        assertNotNull(challengeHeader);
        NostrTURNChallengeEvent challenge = NostrTURNChallengeEvent.parseIncoming(challengeHeader, attacker, 64);

        long vsocketId = 101L;
        UnsignedNostrEvent unsignedConnect = new UnsignedNostrEvent()
            .withKind(25051)
            .createdAt(Instant.now())
            .withTag("t", "connect")
            .withTag("P", roomKeyPair.getPublicKey().asHex())
            .withTag("d", attacker.getSessionId())
            .withTag("i", attacker.getProtocolId())
            .withTag("y", attacker.getApplicationId())
            .withTag("p", victimRemote.getPubkey().asHex(), CHANNEL_LABEL)
            .withContent(
                NGEUtils
                    .getPlatform()
                    .toJSON(Map.of("challenge", challenge.getChallenge(), "vsocketId", Long.toString(vsocketId)))
            );

        SignedNostrEvent signedConnect = NGEUtils.awaitNoThrow(
            attackerSigner.powSign(unsignedConnect, challenge.getRequiredDifficulty())
        );
        ByteBuffer frame = NostrTURNCodec.encodeFrame(
            NostrTURNCodec.encodeHeader(signedConnect),
            vsocketId,
            Collections.emptyList()
        );
        ws.send(frame);

        // Roomproof is mandatory: server should reject and close connection.
        boolean closed = ws.awaitClose(3000);
        assertTrue("Expected websocket to close for missing roomproof", closed);
        assertFalse("Server must not ack unauthorized connect", ws.hasFrameType("ack"));
        ws.closeNow();
    }

    @Test
    public void testQueueAndDrainAfterReciprocalConnect() throws Exception {
        NostrKeyPairSigner aliceSigner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner bobSigner = NostrKeyPairSigner.generate();
        NostrRTCLocalPeer alice = localPeer(aliceSigner, roomKeyPair, "sess-alice");
        NostrRTCLocalPeer bob = localPeer(bobSigner, roomKeyPair, "sess-bob");
        NostrRTCPeer bobRemoteForAlice = new NostrRTCPeer(
            bob.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            bob.getSessionId(),
            roomKeyPair.getPublicKey(),
            null
        );
        NostrRTCPeer aliceRemoteForBob = new NostrRTCPeer(
            alice.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            alice.getSessionId(),
            roomKeyPair.getPublicKey(),
            null
        );

        WsClient aliceWs = WsClient.connect(wsUri);
        SignedNostrEvent challengeHeaderAlice = waitForType(aliceWs, "challenge", 2000);
        NostrTURNChallengeEvent challengeAlice = NostrTURNChallengeEvent.parseIncoming(challengeHeaderAlice, alice, 64);
        long aliceVsocketId = 111L;
        sendValidConnect(aliceWs, alice, bobRemoteForAlice, roomKeyPair, challengeAlice, aliceVsocketId);
        SignedNostrEvent ackAlice = waitForType(aliceWs, "ack", 2000);
        assertNotNull(ackAlice);
        NostrTURNAckEvent.parseIncoming(ackAlice, aliceVsocketId);

        byte[] payload = "queued-before-bob-connects".getBytes();
        byte[] key = NGEUtils.getPlatform().randomBytes(32);
        ByteBuffer dataFrame = NGEUtils.awaitNoThrow(
            NostrTURNDataEvent
                .createOutgoing(alice, bobRemoteForAlice, roomKeyPair, CHANNEL_LABEL, aliceVsocketId, key)
                .encodeToFrame(Collections.singletonList(ByteBuffer.wrap(payload)))
        );
        aliceWs.send(dataFrame);

        WsClient bobWs = WsClient.connect(wsUri);
        SignedNostrEvent challengeHeaderBob = waitForType(bobWs, "challenge", 2000);
        NostrTURNChallengeEvent challengeBob = NostrTURNChallengeEvent.parseIncoming(challengeHeaderBob, bob, 64);
        long bobVsocketId = 222L;
        sendValidConnect(bobWs, bob, aliceRemoteForBob, roomKeyPair, challengeBob, bobVsocketId);
        SignedNostrEvent ackBob = waitForType(bobWs, "ack", 2000);
        assertNotNull(ackBob);
        NostrTURNAckEvent.parseIncoming(ackBob, bobVsocketId);

        SignedNostrEvent bobDataHeader = waitForType(bobWs, "data", 3000);
        assertNotNull("Expected queued frame to drain after reciprocal connect", bobDataHeader);
        ByteBuffer drainedFrame = bobWs.findFrameByType("data");
        assertNotNull(drainedFrame);
        assertEquals("Vsocket must be rewritten for recipient", bobVsocketId, NostrTURNCodec.extractVsocketId(drainedFrame));

        NostrTURNDataEvent incoming = NostrTURNDataEvent.parseIncoming(
            bobDataHeader,
            bob,
            aliceRemoteForBob,
            roomKeyPair,
            CHANNEL_LABEL,
            bobVsocketId
        );
        Queue<ByteBuffer> decoded = new ConcurrentLinkedQueue<ByteBuffer>(
            NGEUtils.awaitNoThrow(incoming.decodeFramePayloads(drainedFrame))
        );
        assertEquals(1, decoded.size());
        byte[] got = new byte[decoded.peek().remaining()];
        decoded.peek().get(got);
        assertArrayEquals(payload, got);

        aliceWs.closeNow();
        bobWs.closeNow();
    }

    @Test
    public void testVsocketScopePerWebsocketForSamePubkey() throws Exception {
        NostrKeyPairSigner sharedSigner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner receiverSigner = NostrKeyPairSigner.generate();
        NostrRTCLocalPeer senderA = localPeer(sharedSigner, roomKeyPair, "sess-sender-a");
        NostrRTCLocalPeer senderB = localPeer(sharedSigner, roomKeyPair, "sess-sender-b");
        NostrRTCLocalPeer receiver = localPeer(receiverSigner, roomKeyPair, "sess-receiver");

        NostrRTCPeer receiverRemote = remotePeer(receiverSigner, roomKeyPair, receiver.getSessionId());
        NostrRTCPeer senderARemote = remotePeer(sharedSigner, roomKeyPair, senderA.getSessionId());
        NostrRTCPeer senderBRemote = remotePeer(sharedSigner, roomKeyPair, senderB.getSessionId());

        WsClient wsSenderA = WsClient.connect(wsUri);
        NostrTURNChallengeEvent challengeA = NostrTURNChallengeEvent.parseIncoming(
            waitForType(wsSenderA, "challenge", 2000),
            senderA,
            64
        );
        long sharedVsocketId = 333L;
        sendValidConnect(wsSenderA, senderA, receiverRemote, roomKeyPair, challengeA, sharedVsocketId);
        assertNotNull(waitForType(wsSenderA, "ack", 2000));

        WsClient wsSenderB = WsClient.connect(wsUri);
        NostrTURNChallengeEvent challengeB = NostrTURNChallengeEvent.parseIncoming(
            waitForType(wsSenderB, "challenge", 2000),
            senderB,
            64
        );
        sendValidConnect(wsSenderB, senderB, receiverRemote, roomKeyPair, challengeB, sharedVsocketId);
        assertNotNull(waitForType(wsSenderB, "ack", 2000));

        WsClient wsReceiver = WsClient.connect(wsUri);
        NostrTURNChallengeEvent challengeR = NostrTURNChallengeEvent.parseIncoming(
            waitForType(wsReceiver, "challenge", 2000),
            receiver,
            64
        );
        long receiverVsocketId = 444L;
        sendValidConnect(wsReceiver, receiver, senderARemote, roomKeyPair, challengeR, receiverVsocketId);
        assertNotNull(waitForType(wsReceiver, "ack", 2000));

        byte[] key = NGEUtils.getPlatform().randomBytes(32);
        byte[] firstPayload = "from-sender-b-before-disconnect".getBytes();
        ByteBuffer dataFromB = NGEUtils.awaitNoThrow(
            NostrTURNDataEvent
                .createOutgoing(senderB, receiverRemote, roomKeyPair, CHANNEL_LABEL, sharedVsocketId, key)
                .encodeToFrame(Collections.singletonList(ByteBuffer.wrap(firstPayload)))
        );
        wsSenderB.send(dataFromB);
        SignedNostrEvent firstDataHeader = waitForType(wsReceiver, "data", 3000);
        assertNotNull(firstDataHeader);

        NostrTURNDisconnectEvent disconnectA = NostrTURNDisconnectEvent.createDisconnect(
            senderA,
            receiverRemote,
            roomKeyPair,
            CHANNEL_LABEL,
            sharedVsocketId,
            "sender-a-closing",
            false
        );
        ByteBuffer disconnectFrame = NGEUtils.awaitNoThrow(disconnectA.encodeToFrame(Collections.emptyList()));
        wsSenderA.send(disconnectFrame);

        byte[] secondPayload = "from-sender-b-after-sender-a-disconnect".getBytes();
        ByteBuffer secondFromB = NGEUtils.awaitNoThrow(
            NostrTURNDataEvent
                .createOutgoing(senderB, receiverRemote, roomKeyPair, CHANNEL_LABEL, sharedVsocketId, key)
                .encodeToFrame(Collections.singletonList(ByteBuffer.wrap(secondPayload)))
        );
        wsSenderB.send(secondFromB);
        SignedNostrEvent secondDataHeader = waitForType(wsReceiver, "data", 3000);
        assertNotNull("Sender B must continue working after Sender A disconnects", secondDataHeader);

        ByteBuffer receivedFrame = wsReceiver.findFrameByType("data");
        assertNotNull(receivedFrame);
        assertEquals(receiverVsocketId, NostrTURNCodec.extractVsocketId(receivedFrame));

        NostrTURNDataEvent decoded = NostrTURNDataEvent.parseIncoming(
            secondDataHeader,
            receiver,
            senderBRemote,
            roomKeyPair,
            CHANNEL_LABEL,
            receiverVsocketId
        );
        Queue<ByteBuffer> payloads = new ConcurrentLinkedQueue<ByteBuffer>(
            NGEUtils.awaitNoThrow(decoded.decodeFramePayloads(receivedFrame))
        );
        assertFalse(payloads.isEmpty());

        wsSenderA.closeNow();
        wsSenderB.closeNow();
        wsReceiver.closeNow();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static NostrRTCLocalPeer localPeer(NostrKeyPairSigner signer, NostrKeyPair roomKeyPair, String sessionId) {
        return new NostrRTCLocalPeer(signer, Collections.emptyList(), APP_ID, PROTOCOL_ID, sessionId, roomKeyPair, null);
    }

    private static NostrRTCPeer remotePeer(NostrKeyPairSigner signer, NostrKeyPair roomKeyPair, String sessionId) {
        return new NostrRTCPeer(
            signer.getKeyPair().getPublicKey(),
            APP_ID,
            PROTOCOL_ID,
            sessionId,
            roomKeyPair.getPublicKey(),
            null
        );
    }

    private static void sendValidConnect(
        WsClient ws,
        NostrRTCLocalPeer local,
        NostrRTCPeer remote,
        NostrKeyPair roomKeyPair,
        NostrTURNChallengeEvent challenge,
        long vsocketId
    ) {
        NostrTURNConnectEvent connect = NostrTURNConnectEvent.createConnect(
            local,
            remote,
            roomKeyPair,
            CHANNEL_LABEL,
            challenge.getChallenge(),
            vsocketId,
            challenge.getRequiredDifficulty()
        );
        ByteBuffer frame = NGEUtils.awaitNoThrow(connect.encodeToFrame(Collections.emptyList()));
        ws.send(frame);
    }

    private static SignedNostrEvent waitForType(WsClient ws, String type, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ByteBuffer frame = ws.pollFrame(100);
            if (frame == null) {
                continue;
            }
            SignedNostrEvent header = NostrTURNCodec.decodeHeader(frame);
            if (type.equals(header.getFirstTagFirstValue("t"))) {
                ws.rememberTypedFrame(type, frame);
                return header;
            }
        }
        return null;
    }

    private static final class WsClient implements WebSocket.Listener {

        private final LinkedBlockingQueue<ByteBuffer> frames = new LinkedBlockingQueue<ByteBuffer>();
        private final Queue<ByteBuffer> typedFrames = new ConcurrentLinkedQueue<ByteBuffer>();
        private final CompletableFuture<Void> closed = new CompletableFuture<Void>();
        private volatile WebSocket ws;

        static WsClient connect(URI uri) {
            WsClient listener = new WsClient();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            listener.ws = client.newWebSocketBuilder().buildAsync(uri, listener).join();
            return listener;
        }

        void send(ByteBuffer frame) {
            byte[] bytes = new byte[frame.remaining()];
            frame.asReadOnlyBuffer().get(bytes);
            ws.sendBinary(ByteBuffer.wrap(bytes), true).join();
        }

        ByteBuffer pollFrame(long timeoutMs) throws Exception {
            return frames.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        void rememberTypedFrame(String type, ByteBuffer frame) {
            typedFrames.add(frame.asReadOnlyBuffer());
        }

        ByteBuffer findFrameByType(String type) {
            ByteBuffer latest = null;
            for (ByteBuffer frame : typedFrames) {
                SignedNostrEvent header = NostrTURNCodec.decodeHeader(frame);
                if (type.equals(header.getFirstTagFirstValue("t"))) {
                    latest = frame.asReadOnlyBuffer();
                }
            }
            return latest;
        }

        boolean hasFrameType(String type) throws Exception {
            for (ByteBuffer frame : typedFrames) {
                SignedNostrEvent header = NostrTURNCodec.decodeHeader(frame);
                if (type.equals(header.getFirstTagFirstValue("t"))) {
                    return true;
                }
            }
            ByteBuffer extra;
            while ((extra = frames.poll(10, TimeUnit.MILLISECONDS)) != null) {
                SignedNostrEvent header = NostrTURNCodec.decodeHeader(extra);
                if (type.equals(header.getFirstTagFirstValue("t"))) {
                    return true;
                }
            }
            return false;
        }

        boolean awaitClose(long timeoutMs) throws Exception {
            try {
                closed.get(timeoutMs, TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        void closeNow() {
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "test-done").join();
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] copy = new byte[data.remaining()];
            data.get(copy);
            frames.offer(ByteBuffer.wrap(copy).asReadOnlyBuffer());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }
}
