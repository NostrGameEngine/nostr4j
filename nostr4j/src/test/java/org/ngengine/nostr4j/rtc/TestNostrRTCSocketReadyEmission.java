/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

public class TestNostrRTCSocketReadyEmission {

    @Test
    public void testCreateChannelEmitsSocketChannelEvent() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("ready-create-channel-test");
        NostrRTCSocket socket = null;
        try {
            socket = newSocket(executor);
            Map<String, Integer> channelEvents = new HashMap<String, Integer>();
            socket.addListener(
                new NostrRTCSocketListener() {
                    @Override
                    public void onRTCSocketRouteUpdate(
                        NostrRTCSocket socket,
                        java.util.Collection<RTCTransportIceCandidate> candidates,
                        String turnServer
                    ) {}

                    @Override
                    public void onRTCSocketClose(NostrRTCSocket socket) {}

                    @Override
                    public void onRTCChannelReady(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCChannel(NostrRTCChannel channel) {
                        channelEvents.merge(channel.getName(), Integer.valueOf(1), Integer::sum);
                    }
                }
            );

            socket.createChannel("alpha");
            socket.createChannel("beta");

            assertEquals(Integer.valueOf(1), channelEvents.get("alpha"));
            assertEquals(Integer.valueOf(1), channelEvents.get("beta"));
            assertEquals(2, channelEvents.size());
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testSetChannelEmitsReady() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("ready-emission-test");
        NostrRTCSocket socket = null;
        try {
            socket = newSocket(executor);
            Map<String, Integer> readyCountByChannel = new HashMap<String, Integer>();
            socket.addListener(
                new NostrRTCSocketListener() {
                    @Override
                    public void onRTCSocketRouteUpdate(
                        NostrRTCSocket socket,
                        java.util.Collection<RTCTransportIceCandidate> candidates,
                        String turnServer
                    ) {}

                    @Override
                    public void onRTCSocketClose(NostrRTCSocket socket) {}

                    @Override
                    public void onRTCChannelReady(NostrRTCChannel channel) {
                        readyCountByChannel.merge(channel.getName(), Integer.valueOf(1), Integer::sum);
                    }

                    @Override
                    public void onRTCChannel(NostrRTCChannel channel) {}
                }
            );

            NostrRTCChannel alpha = socket.createChannel("alpha");
            NostrRTCChannel beta = socket.createChannel("beta");
            alpha.setChannel(new CapturingRTCDataChannel("alpha", "ready-proto", true, true, 0, null));
            beta.setChannel(new CapturingRTCDataChannel("beta", "ready-proto", true, true, 0, null));

            assertEquals(Integer.valueOf(1), readyCountByChannel.get("alpha"));
            assertEquals(Integer.valueOf(1), readyCountByChannel.get("beta"));
            assertEquals(2, readyCountByChannel.size());
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testBinaryMessageReachesExistingLogicalChannelListener() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("binary-before-ready-test");
        NostrRTCSocket socket = null;
        try {
            socket = newSocket(executor);

            final int[] messageCount = new int[] { 0 };
            class CompositeListener implements NostrRTCSocketListener, NostrRTCChannelListener {

                @Override
                public void onRTCSocketRouteUpdate(
                    NostrRTCSocket socket,
                    java.util.Collection<RTCTransportIceCandidate> candidates,
                    String turnServer
                ) {}

                @Override
                public void onRTCSocketClose(NostrRTCSocket socket) {}

                @Override
                public void onRTCChannelReady(NostrRTCChannel channel) {}

                @Override
                public void onRTCChannel(NostrRTCChannel channel) {
                    channel.addListener(this);
                }

                @Override
                public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean isTurn) {
                    messageCount[0]++;
                }

                @Override
                public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

                @Override
                public void onRTCChannelClosed(NostrRTCChannel channel) {}

                @Override
                public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
            }
            CompositeListener listener = new CompositeListener();
            socket.addListener(listener);
            NostrRTCChannel logicalChannel = socket.createChannel("alpha");

            Object rtcListener = readField(socket, "rtcListener");
            java.lang.reflect.Method onBinaryMessage = rtcListener
                .getClass()
                .getDeclaredMethod("onRTCBinaryMessage", RTCDataChannel.class, ByteBuffer.class);
            onBinaryMessage.setAccessible(true);

            RTCDataChannel channel = new CapturingRTCDataChannel("alpha", "ready-proto", true, true, 0, null);
            ByteBuffer framed = frameSinglePacket(logicalChannel, ByteBuffer.wrap(new byte[] { 1 }));
            onBinaryMessage.invoke(rtcListener, channel, framed);

            assertEquals(1, messageCount[0]);
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testRtcInnerFramingDropsDuplicatePacketsAndPreservesPayload() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("binary-duplicate-frame-test");
        NostrRTCSocket socket = null;
        try {
            socket = newSocket(executor);
            NostrRTCChannel channel = socket.createChannel("alpha");
            final int[] messageCount = new int[] { 0 };
            final byte[][] lastPayload = new byte[1][];
            channel.addListener(
                new NostrRTCChannelListener() {
                    @Override
                    public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean isTurn) {
                        ByteBuffer copy = bbf.duplicate();
                        byte[] payload = new byte[copy.remaining()];
                        copy.get(payload);
                        messageCount[0]++;
                        lastPayload[0] = payload;
                    }

                    @Override
                    public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

                    @Override
                    public void onRTCChannelClosed(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
                }
            );

            NostrRTCChannel.PreparedPacket packet = channel.prepareOutgoingPacket(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));
            ByteBuffer framed = frameSinglePacket(channel, packet);
            channel.onRTCSocketMessage(framed.duplicate());
            channel.onRTCSocketMessage(framed.duplicate());

            assertEquals(1, messageCount[0]);
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, lastPayload[0]);
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testTurnFallbackClearsStaleRtcChannels() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("fallback-clears-stale-channel-test");
        NostrRTCSocket socket = null;
        try {
            socket = newSocket(executor);
            NostrRTCChannel channel = socket.createChannel("alpha");
            channel.setChannel(new CapturingRTCDataChannel("alpha", "ready-proto", true, true, 0, null));
            assertTrue(channel.isConnected());

            java.lang.reflect.Method method = NostrRTCSocket.class.getDeclaredMethod("ensureTurnForDownChannels", String.class);
            method.setAccessible(true);
            method.invoke(socket, "test");

            assertFalse("TURN fallback should clear stale RTC channel handles", channel.isConnected());
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testTurnFallbackDoesNotClearHealthyRtcChannels() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("fallback-preserves-healthy-channel-test");
        NostrRTCSocket socket = null;
        try {
            socket = newSocket(executor);
            NostrRTCChannel channel = socket.createChannel("alpha");
            channel.setChannel(new CapturingRTCDataChannel("alpha", "ready-proto", true, true, 0, null));
            assertTrue(channel.isConnected());

            java.lang.reflect.Field connectedField = NostrRTCSocket.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.setBoolean(socket, true);

            java.lang.reflect.Method method = NostrRTCSocket.class.getDeclaredMethod("ensureTurnForDownChannels", String.class);
            method.setAccessible(true);
            method.invoke(socket, "test-healthy");

            assertTrue("Healthy RTC channel handles should be preserved", channel.isConnected());
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testTurnReadyCountsAsUsableTransportAndUpgradeIsRateLimited() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("turn-usable-transport-test");
        NostrRTCSocket socket = null;
        try {
            socket = newSocket(executor);
            NostrRTCChannel channel = socket.createChannel("alpha");

            assertFalse(socket.hasUsableTransport());
            assertFalse(socket.shouldAttemptRtcUpgrade());

            java.lang.reflect.Field activeTransportField = NostrRTCSocket.class.getDeclaredField("activeTransportPath");
            activeTransportField.setAccessible(true);
            activeTransportField.set(socket, NostrRTCSocket.TransportPath.TURN);

            assertTrue(socket.hasUsableTransport());
            assertTrue(socket.shouldAttemptRtcUpgrade());

            java.lang.reflect.Field lastRtcAttemptField = NostrRTCSocket.class.getDeclaredField("lastRtcAttemptSince");
            lastRtcAttemptField.setAccessible(true);
            lastRtcAttemptField.set(socket, java.time.Instant.now());

            assertFalse(socket.shouldAttemptRtcUpgrade());

            lastRtcAttemptField.set(socket, java.time.Instant.now().minusSeconds(61L));
            assertTrue(socket.shouldAttemptRtcUpgrade());

            java.lang.reflect.Field connectedField = NostrRTCSocket.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.setBoolean(socket, true);
            activeTransportField.set(socket, NostrRTCSocket.TransportPath.RTC);

            channel.setChannel(new CapturingRTCDataChannel("alpha", "ready-proto", true, true, 0, null));
            assertTrue(socket.hasUsableTransport());
            assertFalse(socket.shouldAttemptRtcUpgrade());
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testRtcFragmentationRespectsNip44PlaintextLimit() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("fragment-limit-test");
        NostrRTCSocket socket = null;
        try {
            socket = newSocket(executor);
            NostrRTCChannel channel = socket.createChannel("alpha");
            assertEquals(0xFFFF, channel.getMaxFragmentSize());

            int innerFrameHeaderSize = getInnerFrameHeaderSize();
            int payloadChunkSize = channel.getMaxFragmentSize() - innerFrameHeaderSize;
            byte[] payload = new byte[channel.getMaxFragmentSize() * 2 + 123];
            NostrRTCChannel.PreparedPacket packet = channel.prepareOutgoingPacket(ByteBuffer.wrap(payload));

            ByteBuffer[] framed = encodePacketFragments(channel, packet, payloadChunkSize);
            assertTrue("Expected packet to be split into multiple fragments", framed.length > 1);
            for (ByteBuffer fragment : framed) {
                assertTrue(
                    "Fragment plaintext exceeds NIP-44 max plaintext size",
                    fragment.remaining() <= channel.getMaxFragmentSize()
                );
            }
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    private static NostrRTCSocket newSocket(AsyncExecutor executor) throws Exception {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrRTCLocalPeer localPeer = new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            Collections.emptyList(),
            "ready-app",
            "ready-proto",
            roomKeyPair,
            null
        );
        NostrRTCPeer remotePeer = new NostrRTCPeer(
            NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
            "ready-app",
            "ready-proto",
            "remote-ready-session",
            roomKeyPair.getPublicKey(),
            null
        );
        return new NostrRTCSocket(executor, remotePeer, roomKeyPair, localPeer, RTCSettings.DEFAULT, null, null);
    }

    private static Object readField(Object target, String fieldName) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read field " + fieldName, e);
        }
    }

    private static ByteBuffer frameSinglePacket(NostrRTCChannel channel, ByteBuffer payload) throws Exception {
        return frameSinglePacket(channel, channel.prepareOutgoingPacket(payload));
    }

    private static ByteBuffer frameSinglePacket(NostrRTCChannel channel, NostrRTCChannel.PreparedPacket packet)
        throws Exception {
        ByteBuffer[] framed = encodePacketFragments(channel, packet, Integer.MAX_VALUE / 4);
        assertEquals("Expected single fragment in test helper", 1, framed.length);
        return framed[0].asReadOnlyBuffer();
    }

    private static ByteBuffer[] encodePacketFragments(
        NostrRTCChannel channel,
        NostrRTCChannel.PreparedPacket packet,
        int payloadChunkSize
    ) throws Exception {
        java.lang.reflect.Method encodePacketFragments =
            NostrRTCChannel.class.getDeclaredMethod("encodePacketFragments", NostrRTCChannel.PreparedPacket.class, int.class);
        encodePacketFragments.setAccessible(true);
        return (ByteBuffer[]) encodePacketFragments.invoke(channel, packet, payloadChunkSize);
    }

    private static int getInnerFrameHeaderSize() {
        try {
            java.lang.reflect.Field field = NostrRTCChannel.class.getDeclaredField("INNER_FRAME_HEADER_SIZE");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read INNER_FRAME_HEADER_SIZE", e);
        }
    }

    private static final class CapturingRTCDataChannel extends RTCDataChannel {

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
    }
}
