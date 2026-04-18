/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.turn.ref;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class TestTurnServerInternalRegression {

    @Test
    public void testQueuedFrameNotDeliveredWhenSendFails() throws Exception {
        TurnServer server = new TurnServer(12345, NostrKeyPairSigner.generate(), 8, 5, 32);

        NostrKeyPair room = new NostrKeyPair();
        NostrKeyPair senderKeys = new NostrKeyPair();
        NostrKeyPair receiverKeys = new NostrKeyPair();
        TurnVirtualSocket sender = buildSocket(
            101L,
            room.getPublicKey(),
            senderKeys.getPublicKey(),
            receiverKeys.getPublicKey(),
            "sess-a",
            "sess-b"
        );
        TurnVirtualSocket recipient = buildSocket(
            202L,
            room.getPublicKey(),
            receiverKeys.getPublicKey(),
            senderKeys.getPublicKey(),
            "sess-b",
            "sess-a"
        );
        recipient.markAckSent();

        Session recipientSession = sessionProxy(SendBehavior.FAIL);
        TurnClientConnection recipientConnection = new TurnClientConnection(recipientSession, 8);
        recipientConnection.getSockets().put(Long.valueOf(recipient.getVsocketId()), recipient);
        server.clients.put(recipientSession, recipientConnection);

        TurnVirtualSocket.QueuedOutgoingFrame queued = queuedFrame(sender.getVsocketId(), 77);
        assertFalse(NGEUtils.awaitNoThrow(invokeProcessQueuedFrame(server, sender, queued)).booleanValue());
    }

    @Test
    public void testQueuedFrameDeliveredWhenAtLeastOneSendSucceeds() throws Exception {
        TurnServer server = new TurnServer(12346, NostrKeyPairSigner.generate(), 8, 5, 32);

        NostrKeyPair room = new NostrKeyPair();
        NostrKeyPair senderKeys = new NostrKeyPair();
        NostrKeyPair receiverKeys = new NostrKeyPair();
        TurnVirtualSocket sender = buildSocket(
            301L,
            room.getPublicKey(),
            senderKeys.getPublicKey(),
            receiverKeys.getPublicKey(),
            "sess-a",
            "sess-b"
        );
        TurnVirtualSocket recipientOk = buildSocket(
            302L,
            room.getPublicKey(),
            receiverKeys.getPublicKey(),
            senderKeys.getPublicKey(),
            "sess-b",
            "sess-a"
        );
        recipientOk.markAckSent();
        TurnVirtualSocket recipientFail = buildSocket(
            303L,
            room.getPublicKey(),
            receiverKeys.getPublicKey(),
            senderKeys.getPublicKey(),
            "sess-b",
            "sess-a"
        );
        recipientFail.markAckSent();

        Session okSession = sessionProxy(SendBehavior.SUCCEED);
        TurnClientConnection okConnection = new TurnClientConnection(okSession, 8);
        okConnection.getSockets().put(Long.valueOf(recipientOk.getVsocketId()), recipientOk);
        server.clients.put(okSession, okConnection);

        Session failSession = sessionProxy(SendBehavior.FAIL);
        TurnClientConnection failConnection = new TurnClientConnection(failSession, 8);
        failConnection.getSockets().put(Long.valueOf(recipientFail.getVsocketId()), recipientFail);
        server.clients.put(failSession, failConnection);

        TurnVirtualSocket.QueuedOutgoingFrame queued = queuedFrame(sender.getVsocketId(), 88);
        assertTrue(NGEUtils.awaitNoThrow(invokeProcessQueuedFrame(server, sender, queued)).booleanValue());
    }

    @Test
    public void testPriorityDeliveryAckNotStarvedByBlockedDataQueue() throws Exception {
        AtomicInteger dataStarted = new AtomicInteger();
        AtomicInteger ackDelivered = new AtomicInteger();

        TurnVirtualSocket sender = new TurnVirtualSocket(
            401L,
            new NostrKeyPair().getPublicKey(),
            new NostrKeyPair().getPublicKey(),
            new NostrKeyPair().getPublicKey(),
            "sess-prio-a",
            "sess-prio-b",
            "proto",
            "app",
            "default",
            (socket, frame) -> {
                String type = frameType(frame);
                if ("delivery_ack".equals(type)) {
                    ackDelivered.incrementAndGet();
                    return AsyncTask.completed(Boolean.TRUE);
                }
                dataStarted.incrementAndGet();
                return AsyncTask.create((resolve, reject) -> {});
            },
            socket -> Boolean.TRUE
        );
        sender.markAckSent();
        try {
            assertTrue(sender.out(encodedFrame("data", sender.getVsocketId(), 100), 16, false));
            sender.loop();
            waitUntil(() -> dataStarted.get() > 0, 1000, "data queue should start processing");

            assertTrue(sender.out(encodedFrame("delivery_ack", sender.getVsocketId(), 101), 16, true));
            for (int i = 0; i < 20 && ackDelivered.get() == 0; i++) {
                sender.loop();
                Thread.sleep(10L);
            }
            assertTrue("delivery_ack should be drained despite blocked normal data queue", ackDelivered.get() > 0);
        } finally {
            sender.close();
        }
    }

    @Test
    public void testPriorityQueueReservesCapacityWhenNormalBacklogIsFull() throws Exception {
        TurnVirtualSocket sender = new TurnVirtualSocket(
            451L,
            new NostrKeyPair().getPublicKey(),
            new NostrKeyPair().getPublicKey(),
            new NostrKeyPair().getPublicKey(),
            "sess-reserve-a",
            "sess-reserve-b",
            "proto",
            "app",
            "default",
            (socket, frame) -> AsyncTask.create((resolve, reject) -> {}),
            socket -> Boolean.TRUE
        );
        sender.markAckSent();
        try {
            int maxQueued = 3;
            assertTrue(sender.out(encodedFrame("data", sender.getVsocketId(), 201), maxQueued, false));
            assertTrue(sender.out(encodedFrame("data", sender.getVsocketId(), 202), maxQueued, false));
            assertFalse("normal queue should remain bounded and reserve one priority slot", sender.out(encodedFrame("data", sender.getVsocketId(), 203), maxQueued, false));

            assertTrue("delivery_ack should still queue with reserved priority capacity", sender.out(encodedFrame("delivery_ack", sender.getVsocketId(), 204), maxQueued, true));
            assertFalse("priority queue must remain bounded (no unbounded growth)", sender.out(encodedFrame("delivery_ack", sender.getVsocketId(), 205), maxQueued, true));
        } finally {
            sender.close();
        }
    }

    private static TurnVirtualSocket buildSocket(
        long vsocketId,
        NostrPublicKey roomPubkey,
        NostrPublicKey senderPubkey,
        NostrPublicKey targetPubkey,
        String sourceSessionId,
        String targetSessionId
    ) {
        return new TurnVirtualSocket(
            vsocketId,
            roomPubkey,
            senderPubkey,
            targetPubkey,
            sourceSessionId,
            targetSessionId,
            "proto",
            "app",
            "default",
            (socket, frame) -> AsyncTask.completed(Boolean.TRUE),
            socket -> Boolean.TRUE
        );
    }

    @SuppressWarnings("unchecked")
    private static AsyncTask<Boolean> invokeProcessQueuedFrame(
        TurnServer server,
        TurnVirtualSocket sender,
        TurnVirtualSocket.QueuedOutgoingFrame queued
    ) throws Exception {
        Method method = TurnServer.class.getDeclaredMethod(
            "processQueuedFrame",
            TurnVirtualSocket.class,
            TurnVirtualSocket.QueuedOutgoingFrame.class
        );
        method.setAccessible(true);
        return (AsyncTask<Boolean>) method.invoke(server, sender, queued);
    }

    private static TurnVirtualSocket.QueuedOutgoingFrame queuedFrame(long vsocketId, int messageId) throws Exception {
        ByteBuffer encoded = encodedFrame("data", vsocketId, messageId);
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        Constructor<TurnVirtualSocket.QueuedOutgoingFrame> ctor = TurnVirtualSocket.QueuedOutgoingFrame.class.getDeclaredConstructor(
            byte[].class
        );
        ctor.setAccessible(true);
        return ctor.newInstance((Object) bytes);
    }

    private static SignedNostrEvent signedDataHeader() {
        return signedHeader("data");
    }

    private static SignedNostrEvent signedHeader(String type) {
        UnsignedNostrEvent unsigned = new UnsignedNostrEvent().withKind(25051).createdAt(Instant.now()).withTag("t", type).withContent("");
        return NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().sign(unsigned));
    }

    private static ByteBuffer encodedFrame(String type, long vsocketId, int messageId) {
        return NostrTURNCodec.encodeFrame(
            NostrTURNCodec.encodeHeader(signedHeader(type)),
            vsocketId,
            messageId,
            Collections.emptyList()
        );
    }

    private static String frameType(TurnVirtualSocket.QueuedOutgoingFrame frame) {
        ByteBuffer copy = ByteBuffer.wrap(frame.getFrameBytes()).asReadOnlyBuffer();
        SignedNostrEvent header = NostrTURNCodec.decodeHeader(copy);
        return header.getFirstTagFirstValue("t");
    }

    private static void waitUntil(Check check, long timeoutMs, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(message);
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private enum SendBehavior {
        SUCCEED,
        FAIL,
    }

    private static Session sessionProxy(SendBehavior behavior) {
        return (Session) Proxy.newProxyInstance(
            Session.class.getClassLoader(),
            new Class<?>[] { Session.class },
            (proxy, method, args) -> {
                String name = method.getName();
                if ("isOpen".equals(name)) {
                    return Boolean.TRUE;
                }
                if ("sendBinary".equals(name)) {
                    Callback callback = (Callback) args[1];
                    if (behavior == SendBehavior.SUCCEED) {
                        callback.succeed();
                    } else {
                        callback.fail(new RuntimeException("send-failed"));
                    }
                    return null;
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(name)) {
                    return Boolean.valueOf(proxy == args[0]);
                }
                if ("toString".equals(name)) {
                    return "SessionProxy(" + behavior + ")";
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == Boolean.TYPE) {
                    return Boolean.FALSE;
                }
                if (returnType == Integer.TYPE) {
                    return Integer.valueOf(0);
                }
                if (returnType == Long.TYPE) {
                    return Long.valueOf(0L);
                }
                if (returnType == Double.TYPE) {
                    return Double.valueOf(0.0d);
                }
                if (returnType == Float.TYPE) {
                    return Float.valueOf(0.0f);
                }
                if (returnType == Short.TYPE) {
                    return Short.valueOf((short) 0);
                }
                if (returnType == Byte.TYPE) {
                    return Byte.valueOf((byte) 0);
                }
                if (returnType == Character.TYPE) {
                    return Character.valueOf((char) 0);
                }
                return null;
            }
        );
    }
}
