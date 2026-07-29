/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.InternalRoutedTransport;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCDataChannel;

public class TestNostrRTCDirectFastPath {

    @Test
    public void testConnectedRtcBypassesEveryRoutedSendHook() throws Exception {
        NostrKeyPair roomKeys = new NostrKeyPair();
        NostrKeyPairSigner localSigner = NostrKeyPairSigner.generate();
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            localSigner,
            Collections.emptyList(),
            "fast-app",
            "fast-protocol",
            "fast-local",
            roomKeys,
            null
        );
        NostrRTCPeer remote = new NostrRTCPeer(
            NGEUtils.awaitNoThrow(NostrKeyPairSigner.generate().getPublicKey()),
            "fast-app",
            "fast-protocol",
            "fast-remote",
            roomKeys.getPublicKey(),
            null
        );
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("direct-fast-path");
        NostrRTCSocket socket = new NostrRTCSocket(executor, remote, roomKeys, local, RTCSettings.DEFAULT, null, null);
        CountingRoutedTransport routed = new CountingRoutedTransport();
        CapturingRTCDataChannel direct = new CapturingRTCDataChannel();
        try {
            socket.setRoutedTransport(routed);
            setConnected(socket, true);
            NostrRTCChannel channel = socket.createChannel("game");
            channel.setChannel(direct);

            assertTrue(NGEUtils.awaitNoThrow(channel.write(ByteBuffer.wrap(new byte[] { 1, 2, 3 }))));

            assertEquals(1, direct.writes.get());
            assertEquals(0, routed.readyChecks.get());
            assertEquals(0, routed.routeSelections.get());
            assertEquals(0, routed.maximumSizeChecks.get());
            assertEquals(0, routed.routedWrites.get());
        } finally {
            socket.close();
            executor.close();
            roomKeys.destroy();
        }
    }

    private static void setConnected(NostrRTCSocket socket, boolean connected) throws Exception {
        Field field = NostrRTCSocket.class.getDeclaredField("connected");
        field.setAccessible(true);
        field.setBoolean(socket, connected);
    }

    private static final class CountingRoutedTransport implements InternalRoutedTransport {

        private final AtomicInteger readyChecks = new AtomicInteger();
        private final AtomicInteger routeSelections = new AtomicInteger();
        private final AtomicInteger maximumSizeChecks = new AtomicInteger();
        private final AtomicInteger routedWrites = new AtomicInteger();

        @Override
        public boolean isRouteReady(NostrRTCChannel channel) {
            readyChecks.incrementAndGet();
            return true;
        }

        @Override
        public boolean shouldUseRoute(NostrRTCChannel channel) {
            routeSelections.incrementAndGet();
            return true;
        }

        @Override
        public int maximumNormalFrameBytes(NostrRTCChannel channel) {
            maximumSizeChecks.incrementAndGet();
            return 1024;
        }

        @Override
        public AsyncTask<Boolean> writeRouted(NostrRTCChannel channel, ByteBuffer normalFrame) {
            routedWrites.incrementAndGet();
            return AsyncTask.completed(Boolean.TRUE);
        }
    }

    private static final class CapturingRTCDataChannel extends RTCDataChannel {

        private final AtomicInteger writes = new AtomicInteger();

        private CapturingRTCDataChannel() {
            super("game", "fast-protocol", true, true, 0, (Duration) null);
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer data) {
            writes.incrementAndGet();
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
