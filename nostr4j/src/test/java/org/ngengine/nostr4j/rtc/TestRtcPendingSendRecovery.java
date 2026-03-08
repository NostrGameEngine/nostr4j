/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNPool;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCDataChannel;

public class TestRtcPendingSendRecovery {

    private static final String APP_ID = "queue-app";
    private static final String PROTOCOL_ID = "queue-proto";

    @Test
    public void testRoomQueueRecoversWhenDirectWriteNeverCompletes() throws Exception {
        NostrRTCRoom room = null;
        NostrTURNPool turnPool = new NostrTURNPool(24);
        try {
            NostrKeyPair roomKeys = new NostrKeyPair();
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APP_ID,
                PROTOCOL_ID,
                "room-queue-hang-local",
                roomKeys,
                null
            );
            room = new NostrRTCRoom(RTCSettings.DEFAULT, local, roomKeys, new NostrPool(), null, turnPool);

            NostrRTCPeer remote = new NostrRTCPeer(
                org.ngengine.platform.NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
                APP_ID,
                PROTOCOL_ID,
                "room-queue-hang-remote",
                roomKeys.getPublicKey(),
                null
            );
            NostrRTCRoom roomRef = room;

            NostrRTCSocket socket = newSocket(room, remote);
            socket.addListener(readRoomListener(room));
            putConnection(room, remote, socket);

            NostrRTCChannel channel = socket.createChannel("primary");
            HangingRTCDataChannel hanging = new HangingRTCDataChannel("primary", PROTOCOL_ID, true, true, 0, null);
            channel.setChannel(hanging);

            AsyncTask<Void> sendTask = room.send("primary", remote, ByteBuffer.wrap("stuck-then-recover".getBytes(StandardCharsets.UTF_8)));
            assertNotNull(sendTask);
            waitUntil(() -> pendingQueueSize(roomRef, channel) == 1, 2000, "queue should contain hanging payload");

            CapturingRTCDataChannel replacement = new CapturingRTCDataChannel("primary", PROTOCOL_ID, true, true, 0, null);
            channel.setChannel(replacement);

            Thread.sleep(1100);
            restartIfStuck(room, channel);
            org.ngengine.platform.NGEUtils.awaitNoThrow(sendTask);

            waitUntil(() -> replacement.getMessages().size() == 1, 2000, "queue did not recover");
            assertEquals(List.of("stuck-then-recover"), replacement.getMessages());
            assertEquals(0, pendingQueueSize(room, channel));
        } finally {
            if (room != null) {
                room.close();
            }
            turnPool.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static NostrRTCSocketListener readRoomListener(NostrRTCRoom room) throws Exception {
        java.lang.reflect.Field field = NostrRTCRoom.class.getDeclaredField("listener");
        field.setAccessible(true);
        return (NostrRTCSocketListener) field.get(room);
    }

    @SuppressWarnings("unchecked")
    private static void putConnection(NostrRTCRoom room, NostrRTCPeer peer, NostrRTCSocket socket) throws Exception {
        java.lang.reflect.Field field = NostrRTCRoom.class.getDeclaredField("connections");
        field.setAccessible(true);
        Map<NostrRTCPeer, NostrRTCSocket> connections = (Map<NostrRTCPeer, NostrRTCSocket>) field.get(room);
        connections.put(peer, socket);
    }

    private static NostrRTCSocket newSocket(NostrRTCRoom room, NostrRTCPeer remote) throws Exception {
        java.lang.reflect.Method method = NostrRTCRoom.class.getDeclaredMethod("newSocket", NostrRTCPeer.class);
        method.setAccessible(true);
        return (NostrRTCSocket) method.invoke(room, remote);
    }

    @SuppressWarnings("unchecked")
    private static int pendingQueueSize(NostrRTCRoom room, NostrRTCChannel channel) throws Exception {
        java.lang.reflect.Field field = NostrRTCRoom.class.getDeclaredField("pendingSends");
        field.setAccessible(true);
        Map<Object, Object> pending = (Map<Object, Object>) field.get(room);
        Object queue = pending.get(channel);
        if (queue == null) {
            return 0;
        }
        java.lang.reflect.Field queueField = queue.getClass().getDeclaredField("queue");
        queueField.setAccessible(true);
        java.util.Queue<?> entries = (java.util.Queue<?>) queueField.get(queue);
        return entries.size();
    }

    @SuppressWarnings("unchecked")
    private static void restartIfStuck(NostrRTCRoom room, NostrRTCChannel channel) throws Exception {
        java.lang.reflect.Field field = NostrRTCRoom.class.getDeclaredField("pendingSends");
        field.setAccessible(true);
        Map<Object, Object> pending = (Map<Object, Object>) field.get(room);
        Object queue = pending.get(channel);
        if (queue == null) {
            throw new AssertionError("pending queue not found");
        }
        java.lang.reflect.Method method = queue.getClass().getDeclaredMethod("restartIfStuck", long.class);
        method.setAccessible(true);
        method.invoke(queue, Long.valueOf(0L));
    }

    private static void waitUntil(Check check, long timeoutMs, String error) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(error);
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    private static final class CapturingRTCDataChannel extends RTCDataChannel {

        private final List<String> messages = new CopyOnWriteArrayList<String>();

        private CapturingRTCDataChannel(
            String name,
            String protocol,
            boolean ordered,
            boolean reliable,
            int maxRetransmits,
            Duration maxPacketLifeTime
        ) {
            super(name, protocol, ordered, reliable, maxRetransmits, maxPacketLifeTime);
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer data) {
            ByteBuffer copy = data.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            messages.add(new String(bytes, StandardCharsets.UTF_8));
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

        private List<String> getMessages() {
            return messages;
        }
    }

    private static final class HangingRTCDataChannel extends RTCDataChannel {

        private HangingRTCDataChannel(
            String name,
            String protocol,
            boolean ordered,
            boolean reliable,
            int maxRetransmits,
            Duration maxPacketLifeTime
        ) {
            super(name, protocol, ordered, reliable, maxRetransmits, maxPacketLifeTime);
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer data) {
            return AsyncTask.create((res, rej) -> {});
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
}
