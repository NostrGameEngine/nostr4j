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

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNChallengeEvent;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
import org.ngengine.nostr4j.rtc.turn.NostrTURNDataEvent;
import org.ngengine.nostr4j.rtc.turn.NostrTURNDisconnectEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class TestNostrTURNPoolFailures {

    private static final String APP_ID = "turn-failure-app";
    private static final String PROTOCOL_ID = "turn-failure-proto";
    private static final String TURN_URL = "ws://turn-timeout.invalid/turn";

    private NGEPlatform previousPlatform;
    private ImmediateFailurePlatform failurePlatform;

    @Before
    public void setUp() throws Exception {
        previousPlatform = getInstalledPlatform();
        failurePlatform = new ImmediateFailurePlatform();
        installPlatform(failurePlatform);
    }

    @After
    public void tearDown() throws Exception {
        if (previousPlatform != null) {
            installPlatform(previousPlatform);
        }
    }

    @Test
    public void testFailedConnectDoesNotGetClosedByCleanupLoop() throws Exception {
        NostrTURNPool pool = new NostrTURNPool(24);
        try {
            NostrKeyPair room = new NostrKeyPair();
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APP_ID,
                PROTOCOL_ID,
                "turn-failure-local",
                room,
                TURN_URL
            );
            NostrRTCLocalPeer remoteLocal = new NostrRTCLocalPeer(
                NostrKeyPairSigner.generate(),
                Collections.emptyList(),
                APP_ID,
                PROTOCOL_ID,
                "turn-failure-remote",
                room,
                TURN_URL
            );
            NostrRTCPeer remote = new NostrRTCPeer(
                remoteLocal.getPubkey(),
                APP_ID,
                PROTOCOL_ID,
                remoteLocal.getSessionId(),
                room.getPublicKey(),
                TURN_URL
            );

            pool.connect(local, remote, TURN_URL, room, "primary", true, null);

            Thread.sleep(260L);

            assertEquals(0, failurePlatform.getCleanupCloseCount());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testFailedConnectDoesNotLeaveStaleTransportEntry() throws Exception {
        NostrTURNPool pool = new NostrTURNPool(24);
        try {
            NostrTURNChannel channel = pool.connect(
                localPeer("stale-local"),
                remotePeer("stale-remote"),
                TURN_URL,
                room(),
                "primary",
                true,
                null
            );
            assertFalse(channel.isReady());
            waitUntil(() -> !channel.isResurrecting(), 2000, "failed connect should unblock channel resurrection state");
            channel.close("stop-resurrection");
            waitUntil(() -> transportCount(pool) == 0, 3000, "failed connect should remove stale transport entry");
        } finally {
            pool.close();
        }
    }

    @Test
    public void testPoolCloseCancelsPendingWebsocketConnect() throws Exception {
        HangingConnectPlatform hanging = new HangingConnectPlatform();
        installPlatform(hanging);
        NostrTURNPool pool = new NostrTURNPool(24);
        try {
            pool.connect(localPeer("hang-local"), remotePeer("hang-remote"), TURN_URL, room(), "primary", true, null);
            Thread.sleep(200L);
            pool.close();
            waitUntil(() -> hanging.getCloseCount() > 0, 2000, "pool close should cancel unresolved websocket connect");
            assertTrue("pending connect should be closed", hanging.getCloseCount() > 0);
        } finally {
            pool.close();
        }
    }

    @Test
    public void testWebsocketConnectTimeoutUnblocksResurrection() throws Exception {
        HangingConnectPlatform hanging = new HangingConnectPlatform();
        installPlatform(hanging);
        NostrTURNPool pool = new NostrTURNPool(24);
        try {
            NostrKeyPair room = room();
            NostrTURNChannel channel = pool.connect(
                localPeer("timeout-local", room),
                remotePeer("timeout-remote", room),
                TURN_URL,
                room,
                "primary",
                true,
                null
            );
            waitUntil(() -> hanging.getCloseCount() > 0, 9000, "timed-out websocket connect should be closed");
            waitUntil(
                () -> hanging.getTransportCount() >= 2,
                12000,
                "connect timeout should unblock a fresh resurrection attempt"
            );
            channel.close("stop-resurrection");
            waitUntil(() -> transportCount(pool) == 0, 4000, "failed timed-out connect should be removed from transports");
            assertFalse("channel should not remain stuck in resurrection", channel.isResurrecting());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testHalfOpenConnectRetriesAfterAckTimeout() throws Exception {
        NostrKeyPair room = room();
        NostrRTCLocalPeer local = localPeer("half-open-local", room);
        NostrRTCPeer remote = remotePeer("half-open-remote", room);
        HalfOpenPlatform halfOpen = new HalfOpenPlatform(local);
        installPlatform(halfOpen);

        NostrTURNPool pool = new NostrTURNPool(24);
        try {
            NostrTURNChannel channel = pool.connect(local, remote, TURN_URL, room, "primary", true, null);
            assertFalse(channel.isReady());
            waitUntil(
                () -> halfOpen.getConnectFrameCount() >= 2,
                13000,
                "connect handshake should retry after half-open timeout"
            );
            assertTrue(
                "expected at least two TURN connect attempts after timeout recovery",
                halfOpen.getConnectFrameCount() >= 2
            );
            assertFalse("half-open channel must not report connected/ready", channel.isConnected());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testTransportMembershipSwitchCloseAndPreOpenClose() throws Exception {
        NostrKeyPair room = room();
        NostrTURNChannel channel = new NostrTURNChannel(
            localPeer("membership-local", room),
            remotePeer("membership-remote", room),
            TURN_URL,
            room,
            "primary",
            true,
            24
        );

        NostrTURNPool.TURNTransport first = new NostrTURNPool.TURNTransport(new TestWebsocketTransport(false));
        NostrTURNPool.TURNTransport second = new NostrTURNPool.TURNTransport(new TestWebsocketTransport(false));

        channel.setTransport(first);
        assertTrue(first.getUsers().contains(channel));
        channel.setTransport(second);
        assertFalse(first.getUsers().contains(channel));
        assertTrue(second.getUsers().contains(channel));

        channel.close("membership-close");
        assertFalse(second.getUsers().contains(channel));

        NostrTURNChannel preOpen = new NostrTURNChannel(
            localPeer("preopen-local", room),
            remotePeer("preopen-remote", room),
            TURN_URL,
            room,
            "primary",
            true,
            24
        );
        NostrTURNPool.TURNTransport preOpenTransport = new NostrTURNPool.TURNTransport(new TestWebsocketTransport(false));
        preOpen.setTransport(preOpenTransport);
        assertTrue(preOpenTransport.getUsers().contains(preOpen));
        preOpen.close("pre-open-close");
        assertFalse("pre-open close must clean transport membership", preOpenTransport.getUsers().contains(preOpen));
    }

    @Test
    public void testPeerCloseReleasesLocalResourcesWithoutSendingDisconnectEcho() throws Exception {
        NostrKeyPair room = room();
        NostrRTCLocalPeer local = localPeer("peer-close-local", room);
        NostrRTCLocalPeer remoteLocal = localPeer("peer-close-remote", room);
        NostrRTCPeer remote = new NostrRTCPeer(
            remoteLocal.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            remoteLocal.getSessionId(),
            room.getPublicKey(),
            TURN_URL
        );

        NostrTURNChannel channel = new NostrTURNChannel(local, remote, TURN_URL, room, "primary", true, 24);
        RecordingWebsocketTransport transport = new RecordingWebsocketTransport(true);
        channel.setTransport(new NostrTURNPool.TURNTransport(transport));
        setIntField(channel, "state", 2);
        long vsocketId = getLongField(channel, "vSocketId");

        CountDownLatch closed = new CountDownLatch(1);
        channel.addListener(
            new org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener() {
                @Override
                public void onTurnChannelReady(NostrTURNChannel channel) {}

                @Override
                public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {}

                @Override
                public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {}

                @Override
                public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {
                    closed.countDown();
                }
            }
        );

        NostrRTCPeer localAsRemote = new NostrRTCPeer(
            local.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            local.getSessionId(),
            room.getPublicKey(),
            TURN_URL
        );
        NostrTURNDisconnectEvent remoteDisconnect = NostrTURNDisconnectEvent.createDisconnect(
            remoteLocal,
            localAsRemote,
            room,
            "primary",
            vsocketId,
            "peer-normal-close",
            false
        );
        ByteBuffer frame = NGEUtils.awaitNoThrow(remoteDisconnect.encodeToFrame(Collections.emptyList()));
        assertNotNull(frame);

        channel.onBinaryMessage(frame);
        assertTrue("peer close listener should fire", closed.await(2, TimeUnit.SECONDS));
        assertTrue("channel must be closed after peer disconnect", channel.isClosed());
        assertTrue("peer close should release local channel resources", getBooleanField(channel, "localResourcesClosed"));

        // closeFromPeer must release local resources, but must not send disconnect echo.
        assertFalse("peer close must not send disconnect echo", transport.sentTypes().contains("disconnect"));
    }

    @Test
    public void testMalformedFrameWrongChannelIgnoredButTargetedChannelErrors() throws Exception {
        NostrTURNPool pool = new NostrTURNPool(24);
        try {
            NostrKeyPair room = room();
            NostrTURNChannel channelA = new NostrTURNChannel(
                localPeer("mux-a", room),
                remotePeer("mux-a-r", room),
                TURN_URL,
                room,
                "primary",
                true,
                24
            );
            NostrTURNChannel channelB = new NostrTURNChannel(
                localPeer("mux-b", room),
                remotePeer("mux-b-r", room),
                TURN_URL,
                room,
                "primary",
                true,
                24
            );
            RecordingWebsocketTransport raw = new RecordingWebsocketTransport(true);
            NostrTURNPool.TURNTransport shared = new NostrTURNPool.TURNTransport(raw);
            channelA.setTransport(shared);
            channelB.setTransport(shared);

            AtomicInteger errorsA = new AtomicInteger();
            AtomicInteger errorsB = new AtomicInteger();
            channelA.addListener(
                new org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener() {
                    @Override
                    public void onTurnChannelReady(NostrTURNChannel channel) {}

                    @Override
                    public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {}

                    @Override
                    public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {
                        errorsA.incrementAndGet();
                    }

                    @Override
                    public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {}
                }
            );
            channelB.addListener(
                new org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener() {
                    @Override
                    public void onTurnChannelReady(NostrTURNChannel channel) {}

                    @Override
                    public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {}

                    @Override
                    public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {
                        errorsB.incrementAndGet();
                    }

                    @Override
                    public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {}
                }
            );

            long vsocketA = getLongField(channelA, "vSocketId");
            long vsocketB = getLongField(channelB, "vSocketId");
            ByteBuffer malformedForB = malformedDataFrame(vsocketB, 0); // messageId=0 is invalid for TURN data.

            pool.dispatchBinaryFrameToUsers(shared, malformedForB);
            waitUntil(() -> errorsB.get() > 0, 1500, "targeted malformed frame should produce error");
            assertEquals("malformed frame for different channel should be ignored normally", 0, errorsA.get());
            assertTrue("targeted channel must surface malformed frame error", errorsB.get() > 0);
            assertTrue(vsocketA != vsocketB);
        } finally {
            pool.close();
        }
    }

    @Test
    public void testTargetedPayloadDecodeFailureTriggersExplicitErrorHandling() throws Exception {
        NostrKeyPair room = room();
        NostrRTCLocalPeer local = localPeer("decode-local", room);
        NostrRTCLocalPeer remoteLocal = localPeer("decode-remote", room);
        NostrRTCPeer remote = new NostrRTCPeer(
            remoteLocal.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            remoteLocal.getSessionId(),
            room.getPublicKey(),
            TURN_URL
        );

        NostrTURNChannel channel = new NostrTURNChannel(local, remote, TURN_URL, room, "primary", true, 24);
        channel.setTransport(new NostrTURNPool.TURNTransport(new RecordingWebsocketTransport(true)));
        setIntField(channel, "state", 2);

        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        CountDownLatch closed = new CountDownLatch(1);
        channel.addListener(
            new org.ngengine.nostr4j.rtc.listeners.NostrTURNChannelListener() {
                @Override
                public void onTurnChannelReady(NostrTURNChannel channel) {}

                @Override
                public void onTurnChannelMessage(NostrTURNChannel channel, ByteBuffer payload) {}

                @Override
                public void onTurnChannelError(NostrTURNChannel channel, Throwable e) {
                    error.compareAndSet(null, e);
                }

                @Override
                public void onTurnChannelClosed(NostrTURNChannel channel, String reason) {
                    closed.countDown();
                }
            }
        );

        long vsocket = getLongField(channel, "vSocketId");
        byte[] key = NGEUtils.getPlatform().randomBytes(32);
        ByteBuffer validFrame = NGEUtils.awaitNoThrow(
            NostrTURNDataEvent
                .createOutgoing(
                    remoteLocal,
                    new NostrRTCPeer(
                        local.getPubkey(),
                        APP_ID,
                        PROTOCOL_ID,
                        local.getSessionId(),
                        room.getPublicKey(),
                        TURN_URL
                    ),
                    room,
                    "primary",
                    vsocket,
                    key
                )
                .encodeToFrame(Collections.singletonList(ByteBuffer.wrap("decode-failure".getBytes())), 77)
        );
        ByteBuffer corrupted = corruptFirstPayloadByte(validFrame);
        channel.onBinaryMessage(corrupted);

        waitUntil(() -> error.get() != null, 2000, "targeted decode failure should surface explicit error");
        assertTrue("decode failure should close channel to avoid silent blackhole", closed.await(2, TimeUnit.SECONDS));
        assertTrue(channel.isClosed());
    }

    private static NGEPlatform getInstalledPlatform() throws Exception {
        Field field = NGEPlatform.class.getDeclaredField("platform");
        field.setAccessible(true);
        return (NGEPlatform) field.get(null);
    }

    private static void installPlatform(NGEPlatform platform) throws Exception {
        Field field = NGEPlatform.class.getDeclaredField("platform");
        field.setAccessible(true);
        field.set(null, platform);
    }

    private static final class ImmediateFailurePlatform extends JVMAsyncPlatform {

        private final CopyOnWriteArrayList<ImmediateFailureTransport> transports =
            new CopyOnWriteArrayList<ImmediateFailureTransport>();

        @Override
        public WebsocketTransport newTransport() {
            ImmediateFailureTransport transport = new ImmediateFailureTransport();
            transports.add(transport);
            return transport;
        }

        int getCleanupCloseCount() {
            int total = 0;
            for (ImmediateFailureTransport transport : transports) {
                total += transport.getCleanupCloseCount();
            }
            return total;
        }
    }

    private NostrKeyPair room() {
        return new NostrKeyPair();
    }

    private NostrRTCLocalPeer localPeer(String sessionId) {
        return localPeer(sessionId, room());
    }

    private NostrRTCLocalPeer localPeer(String sessionId, NostrKeyPair room) {
        return new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            sessionId,
            room,
            TURN_URL
        );
    }

    private NostrRTCPeer remotePeer(String sessionId) {
        return remotePeer(sessionId, room());
    }

    private NostrRTCPeer remotePeer(String sessionId, NostrKeyPair room) {
        NostrRTCLocalPeer remoteLocal = localPeer(sessionId + "-peer", room);
        return new NostrRTCPeer(
            remoteLocal.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            remoteLocal.getSessionId(),
            room.getPublicKey(),
            TURN_URL
        );
    }

    private static int transportCount(NostrTURNPool pool) throws Exception {
        Field field = NostrTURNPool.class.getDeclaredField("transports");
        field.setAccessible(true);
        Map<?, ?> transports = (Map<?, ?>) field.get(pool);
        return transports.size();
    }

    private static void waitUntil(Check check, long timeoutMs, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError(message);
    }

    private interface Check {
        boolean ok() throws Exception;
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static long getLongField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static boolean getBooleanField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(target);
        if (value instanceof AtomicBoolean) {
            return ((AtomicBoolean) value).get();
        }
        return field.getBoolean(target);
    }

    private static ByteBuffer malformedDataFrame(long vsocketId, int messageId) {
        SignedNostrEvent header = NGEUtils.awaitNoThrow(
            NostrKeyPairSigner
                .generate()
                .sign(new UnsignedNostrEvent().withKind(25051).createdAt(Instant.now()).withTag("t", "data").withContent(""))
        );
        return NostrTURNCodec
            .encodeFrame(NostrTURNCodec.encodeHeader(header), vsocketId, messageId, Collections.emptyList())
            .asReadOnlyBuffer();
    }

    private static ByteBuffer corruptFirstPayloadByte(ByteBuffer frame) {
        byte[] bytes = new byte[frame.remaining()];
        frame.asReadOnlyBuffer().get(bytes);
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] != 0) {
                bytes[i] = (byte) (bytes[i] ^ 0x01);
                return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
            }
        }
        if (bytes.length > 0) {
            bytes[bytes.length - 1] = (byte) 0x01;
        }
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    private static final class ImmediateFailureTransport implements WebsocketTransport {

        private static final String CLEANUP_REASON = "TURN pool cleanup: transport not connected or unused";
        private final CopyOnWriteArrayList<WebsocketTransportListener> listeners =
            new CopyOnWriteArrayList<WebsocketTransportListener>();
        private final AtomicInteger cleanupCloseCount = new AtomicInteger();

        @Override
        public void setMaxMessageSize(int maxMessageSize) {}

        @Override
        public int getMaxMessageSize() {
            return 1024 * 1024 * 10;
        }

        @Override
        public AsyncTask<Void> close(String reason) {
            if (CLEANUP_REASON.equals(reason)) {
                cleanupCloseCount.incrementAndGet();
            }
            for (WebsocketTransportListener listener : listeners) {
                listener.onConnectionClosedByClient(reason);
            }
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            return AsyncTask.failed(new RuntimeException("request timed out"));
        }

        @Override
        public AsyncTask<Void> send(String message) {
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> sendBinary(ByteBuffer payload) {
            return AsyncTask.completed(null);
        }

        @Override
        public void addListener(WebsocketTransportListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(WebsocketTransportListener listener) {
            listeners.remove(listener);
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        int getCleanupCloseCount() {
            return cleanupCloseCount.get();
        }
    }

    private static final class HangingConnectPlatform extends JVMAsyncPlatform {

        private final CopyOnWriteArrayList<TestWebsocketTransport> transports =
            new CopyOnWriteArrayList<TestWebsocketTransport>();

        @Override
        public WebsocketTransport newTransport() {
            TestWebsocketTransport transport = new TestWebsocketTransport(false);
            transport.setHangOnConnect(true);
            transports.add(transport);
            return transport;
        }

        int getCloseCount() {
            int count = 0;
            for (TestWebsocketTransport transport : transports) {
                count += transport.getCloseCount();
            }
            return count;
        }

        int getTransportCount() {
            return transports.size();
        }
    }

    private static final class HalfOpenPlatform extends JVMAsyncPlatform {

        private final NostrRTCLocalPeer challengeSignerPeer;
        private final AtomicInteger connectFrames = new AtomicInteger();

        private HalfOpenPlatform(NostrRTCLocalPeer challengeSignerPeer) {
            this.challengeSignerPeer = challengeSignerPeer;
        }

        @Override
        public WebsocketTransport newTransport() {
            return new TestWebsocketTransport(true) {
                @Override
                protected void onSendBinary(ByteBuffer payload) {
                    String type = frameType(payload);
                    if ("connect".equals(type)) {
                        connectFrames.incrementAndGet();
                    }
                }

                @Override
                protected ByteBuffer challengeFrame() {
                    NostrTURNChallengeEvent challenge = NostrTURNChallengeEvent.createChallenge(challengeSignerPeer, 8, "");
                    return NGEUtils.awaitNoThrow(challenge.encodeToFrame(Collections.emptyList())).asReadOnlyBuffer();
                }
            };
        }

        int getConnectFrameCount() {
            return connectFrames.get();
        }
    }

    private static class TestWebsocketTransport implements WebsocketTransport {

        private final CopyOnWriteArrayList<WebsocketTransportListener> listeners =
            new CopyOnWriteArrayList<WebsocketTransportListener>();
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile boolean connected;
        private volatile boolean hangOnConnect;

        private TestWebsocketTransport(boolean connectImmediately) {
            this.connected = false;
            this.hangOnConnect = !connectImmediately;
        }

        void setHangOnConnect(boolean hangOnConnect) {
            this.hangOnConnect = hangOnConnect;
        }

        @Override
        public void setMaxMessageSize(int maxMessageSize) {}

        @Override
        public int getMaxMessageSize() {
            return 1024 * 1024 * 10;
        }

        @Override
        public AsyncTask<Void> close(String reason) {
            closeCount.incrementAndGet();
            connected = false;
            for (WebsocketTransportListener listener : listeners) {
                listener.onConnectionClosedByClient(reason);
            }
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            if (hangOnConnect) {
                return AsyncTask.create((resolve, reject) -> {});
            }
            connected = true;
            for (WebsocketTransportListener listener : listeners) {
                listener.onConnectionOpen();
            }
            ByteBuffer challenge = challengeFrame();
            if (challenge != null) {
                for (WebsocketTransportListener listener : listeners) {
                    listener.onConnectionBinaryMessage(challenge.asReadOnlyBuffer());
                }
            }
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> send(String message) {
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> sendBinary(ByteBuffer payload) {
            onSendBinary(payload.asReadOnlyBuffer());
            return AsyncTask.completed(null);
        }

        protected void onSendBinary(ByteBuffer payload) {}

        protected ByteBuffer challengeFrame() {
            return null;
        }

        @Override
        public void addListener(WebsocketTransportListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(WebsocketTransportListener listener) {
            listeners.remove(listener);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        int getCloseCount() {
            return closeCount.get();
        }
    }

    private static final class RecordingWebsocketTransport extends TestWebsocketTransport {

        private final CopyOnWriteArrayList<String> sentTypes = new CopyOnWriteArrayList<String>();

        private RecordingWebsocketTransport(boolean connectImmediately) {
            super(connectImmediately);
        }

        @Override
        protected void onSendBinary(ByteBuffer payload) {
            String type = frameType(payload);
            if (type != null) {
                sentTypes.add(type);
            }
        }

        List<String> sentTypes() {
            return new ArrayList<String>(sentTypes);
        }
    }

    private static String frameType(ByteBuffer payload) {
        try {
            return NostrTURNCodec.decodeHeader(payload.asReadOnlyBuffer()).getFirstTagFirstValue("t");
        } catch (Exception e) {
            return null;
        }
    }
}
