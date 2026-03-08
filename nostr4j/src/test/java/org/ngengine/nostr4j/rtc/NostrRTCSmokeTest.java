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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.turn.ref.TurnServer;

public class NostrRTCSmokeTest {

    private static final String APP_ID = "integration-app";
    private static final String PROTOCOL_ID = "integration-proto";

    private static TurnServer turnA;
    private static TurnServer turnB;
    private static String turnUrlA;
    private static String turnUrlB;

    @BeforeClass
    public static void setupClass() throws Exception {
        turnA = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        turnA.start();
        turnUrlA = "ws://127.0.0.1:" + turnA.getPort() + "/turn";

        turnB = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        turnB.start();
        turnUrlB = "ws://127.0.0.1:" + turnB.getPort() + "/turn";
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (turnA != null) {
            turnA.stop();
        }
        if (turnB != null) {
            turnB.stop();
        }
    }

    @Test
    public void testTurnEndToEndSingleServer() throws Exception {
        NostrKeyPair room = new NostrKeyPair();
        NostrRTCLocalPeer alice = peer("alice", "alice-s1", room, turnUrlA);
        NostrRTCLocalPeer bob = peer("bob", "bob-s1", room, turnUrlA);
        NostrRTCPeer bobRemote = remote(bob, room, turnUrlA);
        NostrRTCPeer aliceRemote = remote(alice, room, turnUrlA);

        NostrTURNPool alicePool = new NostrTURNPool(24);
        NostrTURNPool bobPool = new NostrTURNPool(24);
        try {
            NostrTURNChannel aToB = alicePool.connect(alice, bobRemote, turnUrlA, room, "primary", null);
            NostrTURNChannel bFromA = bobPool.connect(bob, aliceRemote, turnUrlA, room, "primary", null);

            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch msgLatch = new CountDownLatch(1);
            String[] payload = new String[1];

            aToB.addListener(new ReadyListener(readyLatch));
            bFromA.addListener(new ReadyAndMessageListener(readyLatch, msgLatch, payload));
            if (aToB.isReady()) {
                readyLatch.countDown();
            }
            if (bFromA.isReady()) {
                readyLatch.countDown();
            }

            assertTrue("TURN channels not ready", readyLatch.await(8, TimeUnit.SECONDS));
            sendUntilDelivered(aToB, "hello-turn", msgLatch, 8);
            assertEquals("hello-turn", payload[0]);
        } finally {
            alicePool.close();
            bobPool.close();
        }
    }

    @Test
    public void testTurnDifferentServersDirectionalRouting() throws Exception {
        NostrKeyPair room = new NostrKeyPair();
        NostrRTCLocalPeer alice = peer("alice", "alice-s2", room, turnUrlA);
        NostrRTCLocalPeer bob = peer("bob", "bob-s2", room, turnUrlB);
        NostrRTCPeer bobRemote = remote(bob, room, turnUrlB);
        NostrRTCPeer aliceRemote = remote(alice, room, turnUrlA);

        NostrTURNPool alicePool = new NostrTURNPool(24);
        NostrTURNPool bobPool = new NostrTURNPool(24);
        try {
            NostrTURNChannel aToB = alicePool.connect(alice, bobRemote, turnUrlB, room, "primary", null);
            NostrTURNChannel bFromA = bobPool.connect(bob, aliceRemote, turnUrlB, room, "primary", null);
            NostrTURNChannel bToA = bobPool.connect(bob, aliceRemote, turnUrlA, room, "primary", null);
            NostrTURNChannel aFromB = alicePool.connect(alice, bobRemote, turnUrlA, room, "primary", null);

            CountDownLatch readyLatch = new CountDownLatch(4);
            CountDownLatch aToBLatch = new CountDownLatch(1);
            CountDownLatch bToALatch = new CountDownLatch(1);
            String[] payloads = new String[2];

            aToB.addListener(new ReadyListener(readyLatch));
            bFromA.addListener(new ReadyAndMessageListener(readyLatch, aToBLatch, payloads, 0));
            bToA.addListener(new ReadyListener(readyLatch));
            aFromB.addListener(new ReadyAndMessageListener(readyLatch, bToALatch, payloads, 1));
            if (aToB.isReady()) {
                readyLatch.countDown();
            }
            if (bFromA.isReady()) {
                readyLatch.countDown();
            }
            if (bToA.isReady()) {
                readyLatch.countDown();
            }
            if (aFromB.isReady()) {
                readyLatch.countDown();
            }

            assertTrue("Directional TURN channels not ready", readyLatch.await(12, TimeUnit.SECONDS));
            sendUntilDelivered(aToB, "a-to-b", aToBLatch, 10);
            sendUntilDelivered(bToA, "b-to-a", bToALatch, 10);
            assertEquals("a-to-b", payloads[0]);
            assertEquals("b-to-a", payloads[1]);
        } finally {
            alicePool.close();
            bobPool.close();
        }
    }

    private static NostrRTCLocalPeer peer(String id, String sessionId, NostrKeyPair room, String turnServer) {
        return new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            sessionId,
            room,
            turnServer
        );
    }

    private static int findFreePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static NostrRTCPeer remote(NostrRTCLocalPeer peer, NostrKeyPair room, String turn) {
        return new NostrRTCPeer(peer.getPubkey(), APP_ID, PROTOCOL_ID, peer.getSessionId(), room.getPublicKey(), turn);
    }

    private static void sendUntilDelivered(NostrTURNChannel channel, String payload, CountDownLatch latch, int timeoutSeconds)
        throws Exception {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        while (System.currentTimeMillis() < deadline) {
            channel.write(ByteBuffer.wrap(data));
            if (latch.await(200, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
        throw new AssertionError("Message not delivered within timeout");
    }

    private static class ReadyListener implements NostrTURNChannelListener {

        private final CountDownLatch ready;

        private ReadyListener(CountDownLatch ready) {
            this.ready = ready;
        }

        @Override
        public void onTurnChannelReady(NostrTURNChannel channel) {
            ready.countDown();
        }

        @Override
        public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {}

        @Override
        public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {}

        @Override
        public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {}
    }

    private static final class ReadyAndMessageListener extends ReadyListener {

        private final CountDownLatch msgLatch;
        private final String[] payloads;
        private final int index;

        private ReadyAndMessageListener(CountDownLatch ready, CountDownLatch msgLatch, String[] payloads) {
            this(ready, msgLatch, payloads, 0);
        }

        private ReadyAndMessageListener(CountDownLatch ready, CountDownLatch msgLatch, String[] payloads, int index) {
            super(ready);
            this.msgLatch = msgLatch;
            this.payloads = payloads;
            this.index = index;
        }

        @Override
        public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {
            ByteBuffer copy = payload.duplicate();
            byte[] data = new byte[copy.remaining()];
            copy.get(data);
            payloads[index] = new String(data, StandardCharsets.UTF_8);
            msgLatch.countDown();
        }
    }
}
