/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.delivery.DeliveryFailures;
import org.ngengine.nostr4j.rtc.delivery.DeliveryRouteUnavailableException;
import org.ngengine.nostr4j.rtc.routing.InternalRoutedTransport;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class TestRoutedQueueRetry {

    @Test
    public void testRetryableCircuitFailureKeepsSamePreparedPacketAndPacketId() throws Exception {
        NostrKeyPair roomKeys = new NostrKeyPair();
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            Collections.emptyList(),
            "queue-route-app",
            "queue-route-protocol",
            "queue-route-local",
            roomKeys,
            null
        );
        NostrRTCPeer remote = new NostrRTCPeer(
            NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
            "queue-route-app",
            "queue-route-protocol",
            "queue-route-remote",
            roomKeys.getPublicKey(),
            null
        );
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("routed-queue-retry");
        NostrRTCSocket socket = new NostrRTCSocket(executor, remote, roomKeys, local, RTCSettings.DEFAULT, null, null);
        RetryOnceRoutedTransport routed = new RetryOnceRoutedTransport();
        socket.setRoutedTransport(routed);
        socket.setPhysicalLinkEnabled(false);
        NostrRTCChannel channel = socket.createChannel("game");
        NostrRTCChannel.PreparedPacket prepared = channel.prepareOutgoingPacket(ByteBuffer.wrap(new byte[] { 4, 5, 6 }));
        BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue = new BlockingPacketQueue<NostrRTCChannel.PreparedPacket>(
            new BlockingPacketQueue.PacketHandler<NostrRTCChannel.PreparedPacket>() {
                @Override
                public AsyncTask<Boolean> handle(NostrRTCChannel.PreparedPacket packet) {
                    return channel.write(packet);
                }

                @Override
                public boolean isReady() {
                    return channel.isReady();
                }

                @Override
                public boolean shouldPauseOnError(Throwable error) {
                    return DeliveryFailures.isRetryable(error);
                }
            },
            Logger.getLogger(TestRoutedQueueRetry.class.getName()),
            "routed queue write failed",
            1000L,
            6000L
        );
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        try {
            queue.enqueue(prepared, ignored -> completed.countDown(), failure::set);
            waitUntil(
                () -> routed.attempts.get() == 1 && executionQueue(queue) == null,
                2000L,
                "queue did not pause after retryable route failure"
            );
            assertEquals(1, queue.size());
            assertEquals(prepared.packetId(), routed.packetIds.get(0).longValue());

            queue.restart();

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(2, routed.attempts.get());
            assertEquals(0, queue.size());
            assertEquals(routed.packetIds.get(0), routed.packetIds.get(1));
            assertEquals(prepared.packetId(), routed.packetIds.get(1).longValue());
        } finally {
            queue.close();
            socket.close();
            executor.close();
            roomKeys.destroy();
        }
    }

    private static Object executionQueue(BlockingPacketQueue<?> queue) throws Exception {
        Field field = BlockingPacketQueue.class.getDeclaredField("executionQueue");
        field.setAccessible(true);
        return field.get(queue);
    }

    private static void waitUntil(Check condition, long timeoutMs, String failure) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.test() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        if (!condition.test()) throw new AssertionError(failure);
    }

    @FunctionalInterface
    private interface Check {
        boolean test() throws Exception;
    }

    private static final class RetryOnceRoutedTransport implements InternalRoutedTransport {

        private final AtomicInteger attempts = new AtomicInteger();
        private final List<Long> packetIds = new ArrayList<Long>();

        @Override
        public boolean isRouteReady(NostrRTCChannel channel) {
            return true;
        }

        @Override
        public boolean shouldUseRoute(NostrRTCChannel channel) {
            return true;
        }

        @Override
        public int maximumNormalFrameBytes(NostrRTCChannel channel) {
            return 64 * 1024;
        }

        @Override
        public synchronized AsyncTask<Boolean> writeRouted(NostrRTCChannel channel, ByteBuffer normalFrame) {
            packetIds.add(Long.valueOf(normalFrame.asReadOnlyBuffer().getLong()));
            if (attempts.incrementAndGet() == 1) {
                return AsyncTask.failed(new DeliveryRouteUnavailableException("test circuit setup failed"));
            }
            return AsyncTask.completed(Boolean.TRUE);
        }
    }
}
