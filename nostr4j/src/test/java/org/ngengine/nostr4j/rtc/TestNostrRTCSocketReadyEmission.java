/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

public class TestNostrRTCSocketReadyEmission {

    @Test
    public void testReadyIsEmittedOncePerChannel() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("ready-emission-test");
        NostrRTCSocket socket = null;
        try {
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
            socket = new NostrRTCSocket(executor, remotePeer, roomKeyPair, localPeer, RTCSettings.DEFAULT, null, null);

            Map<String, Integer> readyCountByChannel = new HashMap<>();
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
                    public void onRTCChannel(NostrRTCChannel channel) {
                       
                    }
                }
            );

            NostrRTCChannel alpha = socket.createChannel("alpha");
            NostrRTCChannel beta = socket.createChannel("beta");

            Method emitReady = NostrRTCChannel.class.getDeclaredMethod("emitChannelReady");
            emitReady.setAccessible(true);
            emitReady.invoke(alpha);
            emitReady.invoke(beta);
            emitReady.invoke(alpha);
            emitReady.invoke(beta);

            assertEquals("alpha should emit once", Integer.valueOf(1), readyCountByChannel.get("alpha"));
            assertEquals("beta should emit once", Integer.valueOf(1), readyCountByChannel.get("beta"));
            assertEquals("two channels should emit ready", 2, readyCountByChannel.size());
            assertTrue("missing alpha ready callback", readyCountByChannel.containsKey("alpha"));
            assertTrue("missing beta ready callback", readyCountByChannel.containsKey("beta"));
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testChannelListenersAreBoundWhenChannelIsCreated() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("channel-bind-test");
        NostrRTCSocket socket = null;
        try {
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
            socket = new NostrRTCSocket(executor, remotePeer, roomKeyPair, localPeer, RTCSettings.DEFAULT, null, null);

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
                public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean isTurn) {
                    messageCount[0]++;
                }

                @Override
                public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

                @Override
                public void onRTCChannelClosed(NostrRTCChannel channel) {}

                @Override
                public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}

                @Override
                public void onRTCChannel(NostrRTCChannel channel) {
                   
                }
            }
            socket.addListener(new CompositeListener());

            NostrRTCChannel alpha = socket.createChannel("alpha");
            alpha.onRTCSocketMessage(ByteBuffer.wrap(new byte[] { 1 }));

            assertEquals("channel listener should already be bound on creation", 1, messageCount[0]);
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
    }

    @Test
    public void testRtcBinaryBeforeReadyStillReachesChannelListener() throws Exception {
        AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor("binary-before-ready-test");
        NostrRTCSocket socket = null;
        try {
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
            socket = new NostrRTCSocket(executor, remotePeer, roomKeyPair, localPeer, RTCSettings.DEFAULT, null, null);

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
                public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean isTurn) {
                    messageCount[0]++;
                }

                @Override
                public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

                @Override
                public void onRTCChannelClosed(NostrRTCChannel channel) {}

                @Override
                public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}

                @Override
                public void onRTCChannel(NostrRTCChannel channel) {
                   
                }
            }
            socket.addListener(new CompositeListener());

            Object rtcListener = readField(socket, "rtcListener");
            Method onBinaryMessage = rtcListener
                .getClass()
                .getDeclaredMethod("onRTCBinaryMessage", RTCDataChannel.class, ByteBuffer.class);
            onBinaryMessage.setAccessible(true);

            RTCDataChannel channel = new CapturingRTCDataChannel("alpha", "ready-proto", true, true, 0, null);
            onBinaryMessage.invoke(rtcListener, channel, ByteBuffer.wrap(new byte[] { 1 }));

            assertEquals("binary message should create/bind logical channel eagerly", 1, messageCount[0]);
        } finally {
            if (socket != null) {
                socket.close();
            }
            executor.close();
        }
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
