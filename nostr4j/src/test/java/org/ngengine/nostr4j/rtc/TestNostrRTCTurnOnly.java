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
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS AND CONTRIBUTORS BE LIABLE
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

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNPool;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.turn.ref.TurnServer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;

public class TestNostrRTCTurnOnly {

    private static final String APPLICATION_ID = "demo-turn-only";
    private static final String PROTOCOL_ID = "demo-turn-only-proto";

    @Test
    public void testForceTURNEmitsSocketChannelReadyAfterTurnBecomesUsable() throws Exception {
        TurnServer server = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        server.start();
        String turnUrl = "ws://127.0.0.1:" + server.getPort() + "/turn";

        AsyncExecutor executorA = NGEUtils.getPlatform().newAsyncExecutor("turn-ready-a");
        AsyncExecutor executorB = NGEUtils.getPlatform().newAsyncExecutor("turn-ready-b");
        NostrTURNPool turnPoolA = new NostrTURNPool();
        NostrTURNPool turnPoolB = new NostrTURNPool();

        try {
            NostrKeyPair roomKeyPair = new NostrKeyPair();
            NostrRTCLocalPeer localA = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APPLICATION_ID,
                PROTOCOL_ID,
                "ready-peer-a",
                roomKeyPair,
                turnUrl
            );
            NostrRTCLocalPeer localB = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APPLICATION_ID,
                PROTOCOL_ID,
                "ready-peer-b",
                roomKeyPair,
                turnUrl
            );

            NostrRTCSocket socketA = new NostrRTCSocket(
                executorA,
                asRemotePeer(localB, roomKeyPair),
                roomKeyPair,
                localA,
                RTCSettings.DEFAULT,
                turnUrl,
                turnPoolA
            );
            NostrRTCSocket socketB = new NostrRTCSocket(
                executorB,
                asRemotePeer(localA, roomKeyPair),
                roomKeyPair,
                localB,
                RTCSettings.DEFAULT,
                turnUrl,
                turnPoolB
            );

            NostrRTCChannel channelA = socketA.createChannel("turn-ready");
            NostrRTCChannel channelB = socketB.createChannel("turn-ready");

            CountDownLatch readyLatch = new CountDownLatch(2);
            AtomicInteger readyEvents = new AtomicInteger();
            NostrRTCSocketListener listener = new NostrRTCSocketListener() {
                @Override
                public void onRTCSocketRouteUpdate(
                    NostrRTCSocket socket,
                    java.util.Collection<org.ngengine.platform.transport.RTCTransportIceCandidate> candidates,
                    String turnServer
                ) {}

                @Override
                public void onRTCSocketClose(NostrRTCSocket socket) {}

                @Override
                public void onRTCChannelReady(NostrRTCChannel channel) {
                    if ("turn-ready".equals(channel.getName())) {
                        readyEvents.incrementAndGet();
                        readyLatch.countDown();
                    }
                }

            };
            socketA.addListener(listener);
            socketB.addListener(listener);

            socketA.setForceTURN(true);
            socketB.setForceTURN(true);
            channelA.write(ByteBuffer.wrap("bootstrap-a".getBytes(StandardCharsets.UTF_8)));
            channelB.write(ByteBuffer.wrap("bootstrap-b".getBytes(StandardCharsets.UTF_8)));

            assertTrue("TURN should emit socket channel ready", readyLatch.await(8, TimeUnit.SECONDS));
            assertTrue("TURN path not ready", isTurnPathReady(channelA) && isTurnPathReady(channelB));
            Thread.sleep(300);
            assertEquals("Ready callback should be emitted once per channel", 2, readyEvents.get());
        } finally {
            turnPoolA.close();
            turnPoolB.close();
            executorA.close();
            executorB.close();
            server.stop();
        }
    }

    @Test
    public void testChannelLevelForceTURNUsesTurnPath() throws Exception {
        TurnServer server = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        server.start();
        String turnUrl = "ws://127.0.0.1:" + server.getPort() + "/turn";

        AsyncExecutor executorA = NGEUtils.getPlatform().newAsyncExecutor("turn-only-a");
        AsyncExecutor executorB = NGEUtils.getPlatform().newAsyncExecutor("turn-only-b");
        NostrTURNPool turnPoolA = new NostrTURNPool();
        NostrTURNPool turnPoolB = new NostrTURNPool();

        try {
            NostrKeyPair roomKeyPair = new NostrKeyPair();
            NostrRTCLocalPeer localA = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APPLICATION_ID,
                PROTOCOL_ID,
                "peer-a",
                roomKeyPair,
                turnUrl
            );
            NostrRTCLocalPeer localB = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APPLICATION_ID,
                PROTOCOL_ID,
                "peer-b",
                roomKeyPair,
                turnUrl
            );

            NostrRTCSocket socketA = new NostrRTCSocket(
                executorA,
                asRemotePeer(localB, roomKeyPair),
                roomKeyPair,
                localA,
                RTCSettings.DEFAULT,
                turnUrl,
                turnPoolA
            );
            NostrRTCSocket socketB = new NostrRTCSocket(
                executorB,
                asRemotePeer(localA, roomKeyPair),
                roomKeyPair,
                localB,
                RTCSettings.DEFAULT,
                turnUrl,
                turnPoolB
            );

            NostrRTCChannel channelA = socketA.createChannel("turn-only");
            NostrRTCChannel channelB = socketB.createChannel("turn-only");
            socketA.setForceTURN(true);
            socketB.setForceTURN(true);
            channelA.write(ByteBuffer.wrap("bootstrap-a".getBytes(StandardCharsets.UTF_8)));
            channelB.write(ByteBuffer.wrap("bootstrap-b".getBytes(StandardCharsets.UTF_8)));
            assertTrue("TURN path not ready", waitForTurnPathReady(channelA, channelB, 8_000));

            CountDownLatch received = new CountDownLatch(1);
            AtomicBoolean viaTurn = new AtomicBoolean(false);
            AtomicReference<String> payload = new AtomicReference<String>();
            channelB.addListener(
                new NostrRTCChannelListener() {
                    @Override
                    public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean isTurn) {
                        byte[] bytes = new byte[bbf.remaining()];
                        bbf.get(bytes);
                        payload.set(new String(bytes, StandardCharsets.UTF_8));
                        viaTurn.set(isTurn);
                        received.countDown();
                    }

                    @Override
                    public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

                    @Override
                    public void onRTCChannelClosed(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
                }
            );

            sendUntilDelivered(channelA, "hello-turn", received, 8_000);

            assertTrue("turn-only channel did not receive payload", received.await(1, TimeUnit.SECONDS));
            assertTrue("payload should arrive through TURN", viaTurn.get());
            assertEquals("hello-turn", payload.get());
        } finally {
            turnPoolA.close();
            turnPoolB.close();
            executorA.close();
            executorB.close();
            server.stop();
        }
    }

    @Test
    public void testTurnInboundIsDeliveredWhenListenerIsAttached() throws Exception {
        TurnServer server = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        server.start();
        String turnUrl = "ws://127.0.0.1:" + server.getPort() + "/turn";

        AsyncExecutor executorA = NGEUtils.getPlatform().newAsyncExecutor("turn-queue-a");
        AsyncExecutor executorB = NGEUtils.getPlatform().newAsyncExecutor("turn-queue-b");
        NostrTURNPool turnPoolA = new NostrTURNPool();
        NostrTURNPool turnPoolB = new NostrTURNPool();
        try {
            NostrKeyPair roomKeyPair = new NostrKeyPair();
            NostrRTCLocalPeer localA = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APPLICATION_ID,
                PROTOCOL_ID,
                "queue-peer-a",
                roomKeyPair,
                turnUrl
            );
            NostrRTCLocalPeer localB = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APPLICATION_ID,
                PROTOCOL_ID,
                "queue-peer-b",
                roomKeyPair,
                turnUrl
            );

            NostrRTCSocket socketA = new NostrRTCSocket(
                executorA,
                asRemotePeer(localB, roomKeyPair),
                roomKeyPair,
                localA,
                RTCSettings.DEFAULT,
                turnUrl,
                turnPoolA
            );
            NostrRTCSocket socketB = new NostrRTCSocket(
                executorB,
                asRemotePeer(localA, roomKeyPair),
                roomKeyPair,
                localB,
                RTCSettings.DEFAULT,
                turnUrl,
                turnPoolB
            );

            NostrRTCChannel channelA = socketA.createChannel("queue-turn");
            NostrRTCChannel channelB = socketB.createChannel("queue-turn");
            socketA.setForceTURN(true);
            socketB.setForceTURN(true);
            channelA.write(ByteBuffer.wrap("bootstrap-a".getBytes(StandardCharsets.UTF_8)));
            channelB.write(ByteBuffer.wrap("bootstrap-b".getBytes(StandardCharsets.UTF_8)));
            assertTrue("TURN path not ready", waitForTurnPathReady(channelA, channelB, 8_000));

            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<String> payload = new AtomicReference<String>();
            AtomicBoolean viaTurn = new AtomicBoolean(false);
            channelB.addListener(
                new NostrRTCChannelListener() {
                    @Override
                    public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean isTurn) {
                        byte[] bytes = new byte[bbf.remaining()];
                        bbf.get(bytes);
                        payload.set(new String(bytes, StandardCharsets.UTF_8));
                        viaTurn.set(isTurn);
                        received.countDown();
                    }

                    @Override
                    public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

                    @Override
                    public void onRTCChannelClosed(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
                }
            );

            channelA.write(ByteBuffer.wrap("queued-turn".getBytes(StandardCharsets.UTF_8)));

            assertTrue("TURN inbound payload was not delivered", received.await(3, TimeUnit.SECONDS));
            assertTrue("Payload should arrive through TURN", viaTurn.get());
            assertEquals("queued-turn", payload.get());
        } finally {
            turnPoolA.close();
            turnPoolB.close();
            executorA.close();
            executorB.close();
            server.stop();
        }
    }

    @Test
    public void testRoomLevelForceTURNAppliesToNewSockets() throws Exception {
        TurnServer server = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        server.start();
        String turnUrl = "ws://127.0.0.1:" + server.getPort() + "/turn";

        NostrTURNPool turnPoolA = new NostrTURNPool();
        NostrTURNPool turnPoolB = new NostrTURNPool();
        NostrRTCRoom roomA = null;
        NostrRTCRoom roomB = null;
        try {
            NostrKeyPair roomKeyPair = new NostrKeyPair();
            NostrRTCLocalPeer localA = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APPLICATION_ID,
                PROTOCOL_ID,
                "room-peer-a",
                roomKeyPair,
                turnUrl
            );
            NostrRTCLocalPeer localB = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APPLICATION_ID,
                PROTOCOL_ID,
                "room-peer-b",
                roomKeyPair,
                turnUrl
            );

            roomA = new NostrRTCRoom(RTCSettings.DEFAULT, localA, roomKeyPair, new NostrPool(), turnUrl, turnPoolA);
            roomB = new NostrRTCRoom(RTCSettings.DEFAULT, localB, roomKeyPair, new NostrPool(), turnUrl, turnPoolB);
            roomA.setForceTURN(true);
            roomB.setForceTURN(true);

            NostrRTCSocket socketA = invokeNewSocket(roomA, asRemotePeer(localB, roomKeyPair));
            NostrRTCSocket socketB = invokeNewSocket(roomB, asRemotePeer(localA, roomKeyPair));

            NostrRTCChannel channelA = socketA.createChannel("room-turn");
            NostrRTCChannel channelB = socketB.createChannel("room-turn");
            channelA.write(ByteBuffer.wrap("bootstrap-a".getBytes(StandardCharsets.UTF_8)));
            channelB.write(ByteBuffer.wrap("bootstrap-b".getBytes(StandardCharsets.UTF_8)));
            assertTrue("TURN path not ready", waitForTurnPathReady(channelA, channelB, 8_000));

            CountDownLatch received = new CountDownLatch(1);
            AtomicBoolean viaTurn = new AtomicBoolean(false);
            channelB.addListener(
                new NostrRTCChannelListener() {
                    @Override
                    public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean isTurn) {
                        viaTurn.set(isTurn);
                        received.countDown();
                    }

                    @Override
                    public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

                    @Override
                    public void onRTCChannelClosed(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
                }
            );

            sendUntilDelivered(channelA, "room-turn", received, 8_000);

            assertTrue("room-level forced TURN did not deliver payload", received.await(1, TimeUnit.SECONDS));
            assertTrue("room-level forced TURN should use TURN path", viaTurn.get());
        } finally {
            if (roomA != null) {
                roomA.close();
            }
            if (roomB != null) {
                roomB.close();
            }
            server.stop();
        }
    }

    private static NostrRTCSocket invokeNewSocket(NostrRTCRoom room, NostrRTCPeer peer) throws Exception {
        java.lang.reflect.Method method = NostrRTCRoom.class.getDeclaredMethod("newSocket", NostrRTCPeer.class);
        method.setAccessible(true);
        return (NostrRTCSocket) method.invoke(room, peer);
    }

    private static NostrRTCPeer asRemotePeer(NostrRTCLocalPeer local, NostrKeyPair roomKeyPair) {
        return new NostrRTCPeer(
            local.getPubkey(),
            local.getApplicationId(),
            local.getProtocolId(),
            local.getSessionId(),
            roomKeyPair.getPublicKey(),
            local.getTurnServer()
        );
    }

    private static int findFreePort() throws Exception {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static boolean waitForTurnPathReady(NostrRTCChannel a, NostrRTCChannel b, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isTurnPathReady(a) && isTurnPathReady(b)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static void sendUntilDelivered(NostrRTCChannel channel, String payload, CountDownLatch received, long timeoutMs)
        throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        while (System.currentTimeMillis() < deadline) {
            channel.write(ByteBuffer.wrap(bytes));
            if (received.await(150, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
        throw new AssertionError("Message not delivered within timeout: " + payload);
    }

    private static boolean isTurnPathReady(NostrRTCChannel channel) {
        try {
            Field turnSendField = NostrRTCChannel.class.getDeclaredField("turnSend");
            Field turnReceiveField = NostrRTCChannel.class.getDeclaredField("turnReceive");
            turnSendField.setAccessible(true);
            turnReceiveField.setAccessible(true);
            Object turnSend = turnSendField.get(channel);
            Object turnReceive = turnReceiveField.get(channel);
            return isTurnReady(turnSend) && isTurnReady(turnReceive);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isTurnReady(Object turnChannelObj) {
        if (!(turnChannelObj instanceof org.ngengine.nostr4j.rtc.turn.NostrTURNChannel)) {
            return false;
        }
        return ((org.ngengine.nostr4j.rtc.turn.NostrTURNChannel) turnChannelObj).isReady();
    }
}
