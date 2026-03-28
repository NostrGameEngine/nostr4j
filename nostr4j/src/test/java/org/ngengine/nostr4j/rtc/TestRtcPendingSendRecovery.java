/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.platform.transport.RTCDataChannel;

public class TestRtcPendingSendRecovery {

    private static final String APP_ID = "queue-app";
    private static final String PROTOCOL_ID = "queue-proto";

    @Test
    public void testPausedQueueRecoversWithoutStuckWarning() throws Exception {
        Logger logger = Logger.getLogger("org.ngengine.nostr4j.rtc.TestRtcPendingSendRecovery.paused");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        CapturingLogHandler logHandler = new CapturingLogHandler();
        logHandler.setLevel(Level.ALL);
        logger.addHandler(logHandler);

        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger(0);
        BlockingPacketQueue<String> queue = new BlockingPacketQueue<String>(
            new BlockingPacketQueue.PacketHandler<String>() {
                @Override
                public AsyncTask<Boolean> handle(String packet) {
                    attempts.incrementAndGet();
                    return AsyncTask.completed(Boolean.valueOf(ready.get()));
                }

                @Override
                public boolean isReady() {
                    return ready.get();
                }
            },
            logger,
            "Failed to send data to peer",
            1000L,
            6000L
        );

        try {
            queue.enqueue("payload");
            waitUntil(() -> queueExecutionQueue(queue) == null, 2000, "queue should pause after an unready write");

            ready.set(true);
            queue.restartIfStuck(0L);

            waitUntil(() -> queue.size() == 0, 2000, "queue should recover once ready");
            assertTrue("queue should have retried after becoming ready", attempts.get() >= 2);
            assertEquals(0, logHandler.getWarningCount("Detected likely stuck queue... recovering"));
        } finally {
            queue.close();
            logger.removeHandler(logHandler);
        }
    }

    @Test
    public void testRetryableErrorPausesQueueUntilRetrySucceeds() throws Exception {
        Logger logger = Logger.getLogger("org.ngengine.nostr4j.rtc.TestRtcPendingSendRecovery.retryable");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        CapturingLogHandler logHandler = new CapturingLogHandler();
        logHandler.setLevel(Level.ALL);
        logger.addHandler(logHandler);

        AtomicInteger attempts = new AtomicInteger(0);
        BlockingPacketQueue<String> queue = new BlockingPacketQueue<String>(
            new BlockingPacketQueue.PacketHandler<String>() {
                @Override
                public AsyncTask<Boolean> handle(String packet) {
                    if (attempts.getAndIncrement() == 0) {
                        return AsyncTask.create((resolve, reject) -> {
                            reject.accept(new NostrTURNChannel.DeliveryAckTimeoutException(54, Long.valueOf(54L)));
                        });
                    }
                    return AsyncTask.completed(Boolean.TRUE);
                }

                @Override
                public boolean shouldPauseOnError(Throwable error) {
                    return NostrTURNChannel.isRetryableWriteFailure(error);
                }
            },
            logger,
            "Failed to send data to peer",
            1000L,
            6000L
        );

        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);

        try {
            queue.enqueue("payload", ignored -> completed.set(true), error -> failed.set(true));

            waitUntil(() -> queueExecutionQueue(queue) == null, 2000, "queue should pause after retryable error");
            assertEquals(1, queue.size());
            assertTrue("caller should remain pending while queue is paused", !completed.get() && !failed.get());

            queue.restartIfStuck(0L);

            waitUntil(() -> completed.get(), 2000, "queue should complete after retry");
            if (failed.get()) {
                fail("retryable timeout should not fail caller");
            }
            assertEquals(0, queue.size());
            assertEquals(2, attempts.get());
            assertEquals(0, logHandler.getWarningCount("Detected likely stuck queue... recovering"));
        } finally {
            queue.close();
            logger.removeHandler(logHandler);
        }
    }

    @Test
    public void testRetryableErrorEventuallyRejectsWhenQueueTimeoutExpires() throws Exception {
        Logger logger = Logger.getLogger("org.ngengine.nostr4j.rtc.TestRtcPendingSendRecovery.timeout");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);

        BlockingPacketQueue<String> queue = new BlockingPacketQueue<String>(
            new BlockingPacketQueue.PacketHandler<String>() {
                @Override
                public AsyncTask<Boolean> handle(String packet) {
                    return AsyncTask.create((resolve, reject) -> {
                        reject.accept(new NostrTURNChannel.DeliveryAckTimeoutException(77, Long.valueOf(77L)));
                    });
                }

                @Override
                public boolean shouldPauseOnError(Throwable error) {
                    return NostrTURNChannel.isRetryableWriteFailure(error);
                }
            },
            logger,
            "Failed to send data to peer",
            10L,
            50L,
            120L
        );

        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicBoolean completed = new AtomicBoolean(false);

        try {
            queue.enqueue("payload", ignored -> completed.set(true), failure::set);

            waitUntil(() -> failure.get() != null, 2000, "queue should reject after queued timeout");
            assertFalse("timed out queue item should not complete successfully", completed.get());
            assertTrue("queue should remove timed out item", queue.size() == 0);
            assertTrue("expected TimeoutException but got: " + failure.get(), failure.get() instanceof TimeoutException);
        } finally {
            queue.close();
        }
    }

    @Test
    public void testQueueCloseRejectsPendingWaiters() throws Exception {
        Logger logger = Logger.getLogger("org.ngengine.nostr4j.rtc.TestRtcPendingSendRecovery.close");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);

        BlockingPacketQueue<String> queue = new BlockingPacketQueue<String>(
            new BlockingPacketQueue.PacketHandler<String>() {
                @Override
                public AsyncTask<Boolean> handle(String packet) {
                    return AsyncTask.completed(Boolean.FALSE);
                }
            },
            logger,
            "Failed to send data to peer",
            10L,
            1000L
        );

        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicBoolean completed = new AtomicBoolean(false);

        queue.enqueue("payload", ignored -> completed.set(true), failure::set);
        waitUntil(() -> queueExecutionQueue(queue) == null, 2000, "queue should pause after unsuccessful write");

        queue.close();

        waitUntil(() -> failure.get() != null, 2000, "queue close should reject pending waiter");
        assertFalse("closed queue should not resolve successfully", completed.get());
        assertTrue(
            "expected queue close rejection but got: " + failure.get(),
            failure.get() instanceof IllegalStateException && "Queue is closed".equals(failure.get().getMessage())
        );
    }

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

            AsyncTask<Void> sendTask = room.send(
                "primary",
                remote,
                ByteBuffer.wrap("stuck-then-recover".getBytes(StandardCharsets.UTF_8))
            );
            assertNotNull(sendTask);
            waitUntil(() -> pendingQueueSize(roomRef, channel) == 1, 2000, "queue should contain hanging payload");

            CapturingRTCDataChannel replacement = new CapturingRTCDataChannel("primary", PROTOCOL_ID, true, true, 0, null);
            channel.setChannel(replacement);

            Thread.sleep(1100);
            restartIfStuck(room, channel);
            org.ngengine.platform.NGEUtils.awaitNoThrow(sendTask);

            waitUntil(() -> replacement.getWriteCount() == 1, 2000, "queue did not recover");
            assertEquals(0, pendingQueueSize(room, channel));
            assertEquals(1, replacement.getWriteCount());
        } finally {
            if (room != null) {
                room.close();
            }
            turnPool.close();
        }
    }

    @Test
    public void testBroadcastSkipsPeersWhoseChannelIsNotWriteReady() throws Exception {
        NostrRTCRoom room = null;
        NostrTURNPool turnPool = new NostrTURNPool(24);
        try {
            NostrKeyPair roomKeys = new NostrKeyPair();
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APP_ID,
                PROTOCOL_ID,
                "room-broadcast-local",
                roomKeys,
                null
            );
            room = new NostrRTCRoom(RTCSettings.DEFAULT, local, roomKeys, new NostrPool(), null, turnPool);

            NostrRTCPeer readyPeer = new NostrRTCPeer(
                org.ngengine.platform.NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
                APP_ID,
                PROTOCOL_ID,
                "room-broadcast-ready",
                roomKeys.getPublicKey(),
                null
            );
            NostrRTCPeer blockedPeer = new NostrRTCPeer(
                org.ngengine.platform.NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
                APP_ID,
                PROTOCOL_ID,
                "room-broadcast-blocked",
                roomKeys.getPublicKey(),
                null
            );

            NostrRTCSocket readySocket = newSocket(room, readyPeer);
            readySocket.addListener(readRoomListener(room));
            putConnection(room, readyPeer, readySocket);
            NostrRTCChannel readyChannel = readySocket.createChannel("primary");
            CapturingRTCDataChannel readyTransport = new CapturingRTCDataChannel("primary", PROTOCOL_ID, true, true, 0, null);
            readyChannel.setChannel(readyTransport);

            NostrRTCSocket blockedSocket = newSocket(room, blockedPeer);
            blockedSocket.addListener(readRoomListener(room));
            putConnection(room, blockedPeer, blockedSocket);
            blockedSocket.createChannel("primary");

            AsyncTask<Void> broadcastTask = room.broadcast(
                "primary",
                ByteBuffer.wrap("fanout".getBytes(StandardCharsets.UTF_8))
            );
            org.ngengine.platform.NGEUtils.awaitNoThrow(broadcastTask);

            assertEquals("Ready peer should receive one broadcast write", 1, readyTransport.getWriteCount());
            assertEquals("Only the ready peer should allocate a send queue", 1, pendingQueueCount(room));
            assertTrue(
                "Blocked peer should not allocate a pending queue entry",
                !hasPendingQueue(room, blockedSocket.getChannel("primary"))
            );
        } finally {
            if (room != null) {
                room.close();
            }
            turnPool.close();
        }
    }

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

    @SuppressWarnings("unchecked")
    private static int pendingQueueCount(NostrRTCRoom room) throws Exception {
        java.lang.reflect.Field field = NostrRTCRoom.class.getDeclaredField("pendingSends");
        field.setAccessible(true);
        Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>> pendingSends =
            (Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>>) field.get(room);
        return pendingSends.size();
    }

    @SuppressWarnings("unchecked")
    private static boolean hasPendingQueue(NostrRTCRoom room, NostrRTCChannel channel) throws Exception {
        java.lang.reflect.Field field = NostrRTCRoom.class.getDeclaredField("pendingSends");
        field.setAccessible(true);
        Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>> pendingSends =
            (Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>>) field.get(room);
        return pendingSends.containsKey(channel);
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

    private static Object queueExecutionQueue(BlockingPacketQueue<?> queue) throws Exception {
        java.lang.reflect.Field field = BlockingPacketQueue.class.getDeclaredField("executionQueue");
        field.setAccessible(true);
        return field.get(queue);
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

    private static final class CapturingLogHandler extends Handler {

        private final List<LogRecord> records = new CopyOnWriteArrayList<LogRecord>();

        @Override
        public void publish(LogRecord record) {
            if (record != null) {
                records.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        private int getWarningCount(String message) {
            int count = 0;
            for (LogRecord record : records) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue() && message.equals(record.getMessage())) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class CapturingRTCDataChannel extends RTCDataChannel {

        private final List<ByteBuffer> messages = new CopyOnWriteArrayList<ByteBuffer>();

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
            ByteBuffer stored = ByteBuffer.allocate(copy.remaining());
            stored.put(copy);
            stored.flip();
            messages.add(stored);
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

        private int getWriteCount() {
            return messages.size();
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
