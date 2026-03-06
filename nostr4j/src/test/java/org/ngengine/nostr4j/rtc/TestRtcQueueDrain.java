package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNPool;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCDataChannel;

public class TestRtcQueueDrain {
    private static final String APP_ID = "queue-app";
    private static final String PROTOCOL_ID = "queue-proto";

    @Test
    public void testQueuedRtcMessageDrainsWhenChannelBecomesReady() throws Exception {
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("rtc-queue-drain-det");
        NostrTURNPool turnPool = new NostrTURNPool(24);
        NostrRTCSocket socket = null;
        NostrRTCChannel channel = null;
        try {
            NostrKeyPair room = new NostrKeyPair();
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APP_ID,
                PROTOCOL_ID,
                "rtc-queue-session",
                room,
                null
            );
            socket = new NostrRTCSocket(executor, room, local, RTCSettings.DEFAULT, null, turnPool);
            channel = new NostrRTCChannel("primary", socket, true, true, Integer.valueOf(0), null);

            AsyncTask<Void> writeTask = channel.write(ByteBuffer.wrap("queued-rtc".getBytes(StandardCharsets.UTF_8)));
            assertNotNull(writeTask);
            assertEquals(1, queueSize(channel));

            CapturingRTCDataChannel dataChannel = new CapturingRTCDataChannel("primary", PROTOCOL_ID, true, true, 0, null);
            channel.setChannel(dataChannel);

            invokeDrainOnce(channel);

            assertEquals(1, dataChannel.getMessages().size());
            assertEquals("queued-rtc", dataChannel.getMessages().get(0));
            assertEquals(0, queueSize(channel));
        } finally {
            if (channel != null) {
                channel.close();
            }
            if (socket != null) {
                socket.close();
            }
            turnPool.close();
            executor.close();
        }
    }

    private static void invokeDrainOnce(NostrRTCChannel channel) {
        try {
            java.lang.reflect.Method method = NostrRTCChannel.class.getDeclaredMethod(
                "drainQueuedMessages",
                Consumer.class,
                Consumer.class
            );
            method.setAccessible(true);
            method.invoke(
                channel,
                new Consumer<Void>() {
                    @Override
                    public void accept(Void v) {
                    }
                },
                new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable t) {
                        throw new RuntimeException(t);
                    }
                }
            );
        } catch (Exception e) {
            throw new IllegalStateException("Cannot invoke RTC drain method", e);
        }
    }

    private static int queueSize(NostrRTCChannel channel) {
        try {
            java.lang.reflect.Field queueField = NostrRTCChannel.class.getDeclaredField("messageQueue");
            queueField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Queue<ByteBuffer> queue = (Queue<ByteBuffer>) queueField.get(channel);
            return queue.size();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot inspect RTC queue size", e);
        }
    }

    private static final class CapturingRTCDataChannel extends RTCDataChannel {
        private final List<String> messages = new CopyOnWriteArrayList<String>();
        private volatile boolean closed;

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
            if (closed) {
                return AsyncTask.failed(new IllegalStateException("channel closed"));
            }
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
            this.closed = true;
            return AsyncTask.completed(null);
        }

        private List<String> getMessages() {
            return messages;
        }
    }
}
