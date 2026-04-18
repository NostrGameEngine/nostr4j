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
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswerSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.rtc.turn.NostrTURNAckEvent;
import org.ngengine.nostr4j.rtc.turn.NostrTURNDataEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.turn.ref.TurnServer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.jvm.JVMAsyncPlatform;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;
import org.ngengine.platform.transport.WebsocketTransport;
import org.ngengine.platform.transport.WebsocketTransportListener;

public class NostrRTCIntegrationTest {

    private static final Logger logger = TestLogger.getRoot(Level.INFO);
    private static final String APP_ID = "integration-app";
    private static final String PROTOCOL_ID = "integration-proto";
    private static final int TURN_MAX_DIFF = 24;
    private static final List<String> REAL_RELAYS = List.of(
        "wss://nostr.rblb.it",
        "wss://relay.ngengine.org",
        "wss://relay2.ngengine.org"
    );

    private static NGEPlatform previousPlatform;
    private static TestPlatform testPlatform;
    private static TurnServer turnServerA;
    private static TurnServer turnServerB;
    private static String turnUrlA;
    private static String turnUrlB;
    private static Logger rootLogger;
    private static Level previousRootLevel;
    private static final Map<Handler, Level> previousHandlerLevels = new IdentityHashMap<Handler, Level>();

    private static NostrRTCLocalPeer localPeer(NostrKeyPairSigner signer, NostrKeyPair roomKeyPair, String sessionId) {
        return new NostrRTCLocalPeer(
            signer,
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            sessionId,
            roomKeyPair,
            "wss://turn.example"
        );
    }

    private static NostrRTCPeer remotePeer(NostrRTCLocalPeer peer, NostrKeyPair roomKeyPair) {
        return new NostrRTCPeer(
            peer.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            peer.getSessionId(),
            roomKeyPair.getPublicKey(),
            "wss://turn.example"
        );
    }

    @Test
    public void testAckEventRoundTrip() {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrKeyPairSigner aliceSigner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner bobSigner = NostrKeyPairSigner.generate();

        NostrRTCLocalPeer aliceLocal = localPeer(aliceSigner, roomKeyPair, "alice-session");
        NostrRTCLocalPeer bobLocal = localPeer(bobSigner, roomKeyPair, "bob-session");

        NostrRTCPeer aliceRemote = remotePeer(aliceLocal, roomKeyPair);
        long vsocketId = 123L;

        ByteBuffer ackFrame = NGEUtils.awaitNoThrow(
            NostrTURNAckEvent.createAck(bobLocal, aliceRemote, roomKeyPair, "default", vsocketId).encodeToFrame(null)
        );
        SignedNostrEvent signed = org.ngengine.nostr4j.rtc.turn.NostrTURNCodec.decodeHeader(ackFrame);

        NostrTURNAckEvent incoming = NostrTURNAckEvent.parseIncoming(
            signed,
            org.ngengine.nostr4j.rtc.turn.NostrTURNCodec.extractVsocketId(ackFrame)
        );

        assertEquals(vsocketId, incoming.getVsocketId());
    }

    @Test
    public void testDataEventEndToEndEncryptionRoundTrip() {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrKeyPairSigner aliceSigner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner bobSigner = NostrKeyPairSigner.generate();

        NostrRTCLocalPeer aliceLocal = localPeer(aliceSigner, roomKeyPair, "alice-session");
        NostrRTCLocalPeer bobLocal = localPeer(bobSigner, roomKeyPair, "bob-session");

        NostrRTCPeer bobRemote = remotePeer(bobLocal, roomKeyPair);
        NostrRTCPeer aliceRemote = remotePeer(aliceLocal, roomKeyPair);

        byte[] encryptionKey = NGEUtils.getPlatform().randomBytes(32);
        long vsocketId = 321L;

        NostrTURNDataEvent aliceOutgoing = NostrTURNDataEvent.createOutgoing(
            aliceLocal,
            bobRemote,
            roomKeyPair,
            "default",
            vsocketId,
            encryptionKey
        );

        ByteBuffer payload = ByteBuffer.wrap("hello over turn".getBytes());
        ByteBuffer frame = NGEUtils.awaitNoThrow(aliceOutgoing.encodeToFrame(List.of(payload)));

        SignedNostrEvent header = org.ngengine.nostr4j.rtc.turn.NostrTURNCodec.decodeHeader(frame);
        NostrTURNDataEvent bobIncoming = NostrTURNDataEvent.parseIncoming(
            header,
            bobLocal,
            aliceRemote,
            roomKeyPair,
            "default",
            org.ngengine.nostr4j.rtc.turn.NostrTURNCodec.extractVsocketId(frame)
        );

        Collection<ByteBuffer> decoded = NGEUtils.awaitNoThrow(bobIncoming.decodeFramePayloads(frame));
        assertEquals(1, decoded.size());

        ByteBuffer only = decoded.iterator().next();
        byte[] bytes = new byte[only.remaining()];
        only.get(bytes);
        assertArrayEquals("hello over turn".getBytes(), bytes);
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        // rootLogger = LogManager.getLogManager().getLogger("");
        // if (rootLogger == null) {
        //     rootLogger = Logger.getLogger("");
        // }
        // previousRootLevel = rootLogger.getLevel();
        // rootLogger.setLevel(Level.INFO);
        // previousHandlerLevels.clear();
        // for (Handler h : rootLogger.getHandlers()) {
        //     previousHandlerLevels.put(h, h.getLevel());
        //     h.setLevel(Level.INFO);
        // }

        // LogManager logManager = LogManager.getLogManager();
        // Enumeration<String> names = logManager.getLoggerNames();
        // while (names.hasMoreElements()) {
        //     String name = names.nextElement();
        //     Logger logger = logManager.getLogger(name);
        //     if (logger != null) {
        //         logger.setLevel(Level.INFO);
        //     }
        // }

        previousPlatform = getInstalledPlatform();
        testPlatform = new TestPlatform();
        installPlatform(testPlatform);

        turnServerA = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        turnServerA.start();
        turnUrlA = "ws://127.0.0.1:" + turnServerA.getPort() + "/turn";

        turnServerB = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        turnServerB.start();
        turnUrlB = "ws://127.0.0.1:" + turnServerB.getPort() + "/turn";
    }

    @AfterClass
    public static void afterClass() throws Exception {
        if (turnServerA != null) {
            turnServerA.stop();
        }
        if (turnServerB != null) {
            turnServerB.stop();
        }
        if (previousPlatform != null) {
            installPlatform(previousPlatform);
        }
        if (rootLogger != null) {
            rootLogger.setLevel(previousRootLevel);
            for (Handler h : rootLogger.getHandlers()) {
                Level oldLevel = previousHandlerLevels.get(h);
                if (oldLevel != null) {
                    h.setLevel(oldLevel);
                }
            }
        }
    }

    @Test
    public void testOfferAnswerRouteSignalsAreEncryptedOnWire() {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrKeyPairSigner aliceSigner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner bobSigner = NostrKeyPairSigner.generate();

        NostrRTCLocalPeer aliceLocal = new NostrRTCLocalPeer(
            aliceSigner,
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            "alice-signal-wire",
            roomKeyPair,
            turnUrlA
        );
        NostrRTCLocalPeer bobLocal = new NostrRTCLocalPeer(
            bobSigner,
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            "bob-signal-wire",
            roomKeyPair,
            turnUrlB
        );
        NostrRTCPeer bobRemote = new NostrRTCPeer(
            bobLocal.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            bobLocal.getSessionId(),
            roomKeyPair.getPublicKey(),
            turnUrlB
        );

        String offerSdp = "v=0\\r\\no=- 1 1 IN IP4 127.0.0.1\\r\\n";
        String answerSdp = "v=0\\r\\no=- 2 1 IN IP4 127.0.0.1\\r\\n";
        RTCTransportIceCandidate candidate = new RTCTransportIceCandidate("candidate:1 1 UDP 1 127.0.0.1 9999 typ host", "0");

        NostrRTCOfferSignal offerOut = new NostrRTCOfferSignal(aliceSigner, roomKeyPair, aliceLocal, offerSdp);
        SignedNostrEvent offerEvent = NGEUtils.awaitNoThrow(offerOut.toEvent(bobLocal.getPubkey()));
        assertNotNull(offerEvent);
        assertFalse("Offer leaked plaintext in event content", offerEvent.getContent().contains(offerSdp));
        NostrRTCOfferSignal offerIn = new NostrRTCOfferSignal(bobSigner, roomKeyPair, offerEvent);
        assertEquals(offerSdp, offerIn.getOfferString());

        NostrRTCAnswerSignal answerOut = new NostrRTCAnswerSignal(bobSigner, roomKeyPair, bobRemote, answerSdp);
        SignedNostrEvent answerEvent = NGEUtils.awaitNoThrow(answerOut.toEvent(aliceLocal.getPubkey()));
        assertNotNull(answerEvent);
        assertFalse("Answer leaked plaintext in event content", answerEvent.getContent().contains(answerSdp));
        NostrRTCAnswerSignal answerIn = new NostrRTCAnswerSignal(aliceSigner, roomKeyPair, answerEvent);
        assertEquals(answerSdp, answerIn.getSdp());

        NostrRTCRouteSignal routeOut = new NostrRTCRouteSignal(
            aliceSigner,
            roomKeyPair,
            aliceLocal,
            List.of(candidate),
            turnUrlA
        );
        SignedNostrEvent routeEvent = NGEUtils.awaitNoThrow(routeOut.toEvent(bobLocal.getPubkey()));
        assertNotNull(routeEvent);
        assertFalse(
            "Route leaked plaintext candidate in event content",
            routeEvent.getContent().contains(candidate.getCandidate())
        );
        NostrRTCRouteSignal routeIn = new NostrRTCRouteSignal(bobSigner, roomKeyPair, routeEvent);
        assertEquals(turnUrlA, routeIn.getTurnServer());
        assertEquals(1, routeIn.getCandidates().size());
        assertEquals(candidate.getCandidate(), routeIn.getCandidates().iterator().next().getCandidate());
    }

    @Test
    public void testDirectRtcWithMultipleChannels() throws Exception {
        String aliceSession = "alice-rtc-direct";
        String bobSession = "bob-rtc-direct";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.directStable());
        testPlatform.setProfile(bobSession, TransportProfile.directStable());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, null, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, null, null);
        try {
            connect(alice, bob);

            NostrRTCChannel aliceDefault = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobDefault = bob.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel aliceAlt = alice.socket.createChannel("sync", true, true, 0, null);
            NostrRTCChannel bobAlt = bob.socket.createChannel("sync", true, true, 0, null);

            awaitCondition(
                () -> aliceDefault.isConnected() && bobDefault.isConnected() && aliceAlt.isConnected() && bobAlt.isConnected(),
                5000,
                "RTC channels did not become ready"
            );

            MessageCapture defaultCapture = new MessageCapture(1);
            MessageCapture altCapture = new MessageCapture(1);
            bobDefault.addListener(defaultCapture);
            bobAlt.addListener(altCapture);

            aliceDefault.write(bytes("hello-default"));
            aliceAlt.write(bytes("hello-sync"));

            assertTrue(defaultCapture.await(5));
            assertTrue(altCapture.await(5));
            assertEquals("hello-default", defaultCapture.messages.get(0));
            assertEquals("hello-sync", altCapture.messages.get(0));
            assertFalse(defaultCapture.turnFlags.get(0).booleanValue());
            assertFalse(altCapture.turnFlags.get(0).booleanValue());
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testCreateChannelDefaultOptionsAndReadyCallbacks() throws Exception {
        String aliceSession = "alice-default-options";
        String bobSession = "bob-default-options";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.directStable());
        testPlatform.setProfile(bobSession, TransportProfile.directStable());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, null, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, null, null);
        try {
            connect(alice, bob);

            CountDownLatch aliceReady = new CountDownLatch(1);
            CountDownLatch bobReady = new CountDownLatch(1);
            alice.socket.addListener(
                new NostrRTCSocketListener() {
                    @Override
                    public void onRTCSocketRouteUpdate(
                        NostrRTCSocket socket,
                        Collection<RTCTransportIceCandidate> candidates,
                        String turnServer
                    ) {}

                    @Override
                    public void onRTCSocketClose(NostrRTCSocket socket) {}

                    @Override
                    public void onRTCChannelReady(NostrRTCChannel channel) {
                        if ("defaults".equals(channel.getName())) {
                            aliceReady.countDown();
                        }
                    }

                    @Override
                    public void onRTCChannel(NostrRTCChannel channel) {}
                }
            );
            bob.socket.addListener(
                new NostrRTCSocketListener() {
                    @Override
                    public void onRTCSocketRouteUpdate(
                        NostrRTCSocket socket,
                        Collection<RTCTransportIceCandidate> candidates,
                        String turnServer
                    ) {}

                    @Override
                    public void onRTCSocketClose(NostrRTCSocket socket) {}

                    @Override
                    public void onRTCChannelReady(NostrRTCChannel channel) {
                        if ("defaults".equals(channel.getName())) {
                            bobReady.countDown();
                        }
                    }

                    @Override
                    public void onRTCChannel(NostrRTCChannel channel) {}
                }
            );

            NostrRTCChannel aliceChannel = alice.socket.createChannel("defaults");
            NostrRTCChannel bobChannel = bob.socket.createChannel("defaults");

            assertTrue("Alice custom channel readiness callback not fired", aliceReady.await(5, TimeUnit.SECONDS));
            assertTrue("Bob custom channel readiness callback not fired", bobReady.await(5, TimeUnit.SECONDS));
            awaitCondition(
                () -> aliceChannel.isConnected() && bobChannel.isConnected(),
                5000,
                "Default-options channel not connected"
            );

            MessageCapture capture = new MessageCapture(1);
            bobChannel.addListener(capture);
            aliceChannel.write(bytes("hello-default-options"));

            assertTrue(capture.await(5));
            assertEquals("hello-default-options", capture.messages.get(0));
            assertFalse(capture.turnFlags.get(0).booleanValue());
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testStrictRtcFirstDoesNotPrewarmTurnWhenRtcHealthy() throws Exception {
        String aliceSession = "alice-rtc-strict";
        String bobSession = "bob-rtc-strict";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.directStable());
        testPlatform.setProfile(bobSession, TransportProfile.directStable());

        TurnServer strictTurnServer = new TurnServer(findFreePort(), NostrKeyPairSigner.generate(), 10, 30);
        strictTurnServer.start();
        String strictTurnUrl = "ws://127.0.0.1:" + strictTurnServer.getPort() + "/turn";

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, strictTurnUrl, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, strictTurnUrl, null);
        try {
            connect(alice, bob);
            NostrRTCChannel aliceChannel = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobChannel = bob.socket.createChannel("primary", true, true, 0, null);

            awaitCondition(() -> aliceChannel.isConnected() && bobChannel.isConnected(), 5000, "RTC channel not ready");

            MessageCapture capture = new MessageCapture(1);
            bobChannel.addListener(capture);
            sendUntilMessageReceived(aliceChannel, capture, "rtc-only", 4000, Boolean.FALSE);
            assertTrue(capture.await(2));
            assertEquals("rtc-only", capture.messages.get(0));
            assertFalse(capture.turnFlags.get(0).booleanValue());

            Thread.sleep(300);
            assertTrue(
                "TURN should not be pre-warmed while RTC is healthy",
                testPlatform.getCapturedBinaryFrames(strictTurnUrl).isEmpty()
            );
        } finally {
            alice.close();
            bob.close();
            strictTurnServer.stop();
        }
    }

    @Test
    public void testTurnFallbackAndEncryptedPayloadNotLeakedAsPlaintext() throws Exception {
        String aliceSession = "alice-turn-only";
        String bobSession = "bob-turn-only";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        try {
            connect(alice, bob);

            NostrRTCChannel aliceDefault = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobDefault = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture capture = new MessageCapture(1);
            bobDefault.addListener(capture);

            String plain = "secret-payload-001";
            sendUntilMessageReceived(aliceDefault, capture, plain, 8000);
            assertTrue(capture.await(2));
            assertEquals(plain, capture.messages.get(0));
            assertTrue(capture.turnFlags.get(0).booleanValue());

            byte[] needle = plain.getBytes(StandardCharsets.UTF_8);
            List<byte[]> frames = testPlatform.getCapturedBinaryFrames(turnUrlA);
            assertFalse("Expected TURN binary frames", frames.isEmpty());
            for (byte[] frame : frames) {
                assertFalse("TURN frame leaked plaintext payload", contains(frame, needle));
            }
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testFallbackToTurnAndRestoreToRtcAfterTransientDrop() throws Exception {
        String aliceSession = "alice-flap";
        String bobSession = "bob-flap";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.transientDrop(4_000));
        testPlatform.setProfile(bobSession, TransportProfile.transientDrop(4_000));

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        try {
            connect(alice, bob);
            NostrRTCChannel aliceDefault = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobDefault = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture capture = new MessageCapture(3);
            bobDefault.addListener(capture);

            awaitCondition(
                new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        return aliceDefault.isConnected() && bobDefault.isConnected();
                    }
                },
                5000,
                "RTC channel not ready"
            );

            sendUntilMessageReceived(aliceDefault, capture, "first-rtc", 4000);

            Thread.sleep(700);
            sendUntilMessageReceived(aliceDefault, capture, "second-turn", 10000);

            awaitCondition(
                new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        return aliceDefault.isConnected() && bobDefault.isConnected();
                    }
                },
                10000,
                "RTC channel not restored"
            );
            sendUntilMessageReceived(aliceDefault, capture, "third-rtc", 10000, Boolean.FALSE);

            int firstRtcIdx = capture.firstIndexOf("first-rtc");
            int secondTurnIdx = capture.firstIndexOf("second-turn");
            int thirdRtcIdx = capture.firstIndexOf("third-rtc");
            assertTrue(firstRtcIdx >= 0);
            assertTrue(secondTurnIdx >= 0);
            assertTrue(thirdRtcIdx >= 0);
            assertFalse(capture.turnFlags.get(firstRtcIdx).booleanValue());
            assertFalse(capture.turnFlags.get(thirdRtcIdx).booleanValue());
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testPeriodicRtcUpgradeAttemptsDoNotDisruptTurnOrderingOrWriteReadiness() throws Exception {
        String aliceSession = "alice-upgrade-attempt-turn-stability";
        String bobSession = "bob-upgrade-attempt-turn-stability";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        try {
            connect(alice, bob);
            NostrRTCChannel aliceCh = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobCh = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture capture = new MessageCapture(8);
            bobCh.addListener(capture);

            for (int i = 0; i < 8; i++) {
                if ((i % 2) == 0) {
                    triggerBestEffortRtcUpgradeAttempt(alice, bob);
                }
                String message = "upgrade-turn-" + i;
                sendUntilMessageReceived(aliceCh, capture, message, 10000, Boolean.TRUE);
                assertTrue("TURN write path became unavailable while upgrade attempts were running", aliceCh.isReady());
            }

            int previous = -1;
            for (int i = 0; i < 8; i++) {
                String expected = "upgrade-turn-" + i;
                int idx = capture.firstIndexOf(expected);
                assertTrue("Missing expected message: " + expected, idx >= 0);
                assertTrue("Message ordering regression detected for: " + expected, idx > previous);
                assertTrue("Expected TURN transport for message: " + expected, capture.turnFlags.get(idx).booleanValue());
                previous = idx;
            }
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testTurnJitterDoesNotBreakFlowAndTurnDedupRemainsEffective() throws Exception {
        String aliceSession = "alice-turn-jitter-dedup";
        String bobSession = "bob-turn-jitter-dedup";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        try {
            connect(alice, bob);
            NostrRTCChannel aliceCh = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobCh = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture capture = new MessageCapture(6);
            bobCh.addListener(capture);

            for (int i = 0; i < 5; i++) {
                if (i > 0) {
                    forceCloseTurnTransports(alice.turnPool);
                    forceCloseTurnTransports(bob.turnPool);
                }
                String message = "jitter-turn-" + i;
                sendUntilMessageReceived(aliceCh, capture, message, 15000, Boolean.TRUE);
            }

            NostrRTCChannel.PreparedPacket duplicatePacket = aliceCh.prepareOutgoingPacket(bytes("turn-dedup-payload"));
            ByteBuffer framedDuplicatePacket = frameSinglePacket(aliceCh, duplicatePacket);
            bobCh.onTURNSocketMessage(framedDuplicatePacket.duplicate());
            bobCh.onTURNSocketMessage(framedDuplicatePacket.duplicate());

            awaitCondition(
                () -> capture.containsMessageOnTransport("turn-dedup-payload", true),
                3000,
                "TURN dedup payload was not observed"
            );

            assertEquals(
                "Duplicate TURN payload should be delivered exactly once",
                1,
                capture.countMessageOnTransport("turn-dedup-payload", true)
            );

            int previous = -1;
            for (int i = 0; i < 5; i++) {
                String expected = "jitter-turn-" + i;
                int idx = capture.firstIndexOf(expected);
                assertTrue("Missing expected message: " + expected, idx >= 0);
                assertTrue("Out-of-order TURN delivery detected for: " + expected, idx > previous);
                previous = idx;
            }
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testLocalCloseDuringHalfOpenTurnConnectCleansServerSocketState() throws Exception {
        String localSession = "half-open-close-local";
        testPlatform.reset();
        testPlatform.setProfile(localSession, TransportProfile.rejectRtc());
        testPlatform.dropIncomingTurnFrameType(turnUrlA, "ack", 1);

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            localSession,
            roomKeyPair,
            turnUrlA
        );
        NostrRTCLocalPeer remoteLocal = new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            "half-open-close-remote",
            roomKeyPair,
            turnUrlA
        );
        NostrRTCPeer remote = new NostrRTCPeer(
            remoteLocal.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            remoteLocal.getSessionId(),
            roomKeyPair.getPublicKey(),
            turnUrlA
        );

        NostrTURNPool pool = new NostrTURNPool(TURN_MAX_DIFF);
        NostrTURNChannel channel = null;
        int baseline = turnLogicalSocketCount(turnServerA);
        try {
            channel = pool.connect(local, remote, turnUrlA, roomKeyPair, "primary", true, null);
            awaitCondition(
                () -> turnLogicalSocketCount(turnServerA) > baseline,
                5000,
                "server did not accept half-open TURN connect"
            );
            assertFalse("connect-ack is intentionally dropped; channel must remain half-open/not-ready", channel.isReady());

            channel.close("half-open-local-close");
            awaitCondition(
                () -> turnLogicalSocketCount(turnServerA) == baseline,
                5000,
                "local close during half-open connect should clean server logical socket"
            );
            assertTrue(channel.isClosed());
        } finally {
            if (channel != null && !channel.isClosed()) {
                channel.close("test-cleanup");
            }
            pool.close();
        }
    }

    @Test
    public void testTurnWriteSurvivesAckDelayBeyondLegacyTimeout() throws Exception {
        String aliceSession = "alice-turn-ack-timeout-window";
        String bobSession = "bob-turn-ack-timeout-window";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        try {
            connect(alice, bob);
            NostrRTCChannel aliceCh = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobCh = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture warmupCapture = new MessageCapture(1);
            bobCh.addListener(warmupCapture);
            sendUntilMessageReceived(aliceCh, warmupCapture, "warmup-turn", 10000, Boolean.TRUE);
            awaitCondition(() -> isTurnPathReady(aliceCh) && isTurnPathReady(bobCh), 5000, "TURN path not ready");

            testPlatform.delayTurnDeliveryAck(turnUrlA, 1, 6500L);
            MessageCapture delayedCapture = new MessageCapture(1);
            bobCh.addListener(delayedCapture);

            boolean delivered = NGEUtils.awaitNoThrow(
                aliceCh.write(ByteBuffer.wrap("delayed-ack-window".getBytes(StandardCharsets.UTF_8)))
            );
            assertTrue("TURN write should survive ack delay beyond legacy 5s timeout", delivered);
            assertTrue("receiver did not observe delayed-ack payload", delayedCapture.await(2));
            assertEquals(1, delayedCapture.countMessageOnTransport("delayed-ack-window", true));
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testDelayedTurnDeliveryAckRecoversQueuedWritesWithoutDuplicateDelivery() throws Exception {
        String aliceSession = "alice-turn-delayed-ack";
        String bobSession = "bob-turn-delayed-ack";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue = null;
        try {
            connect(alice, bob);
            NostrRTCChannel aliceCh = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobCh = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture warmupCapture = new MessageCapture(1);
            bobCh.addListener(warmupCapture);
            sendUntilMessageReceived(aliceCh, warmupCapture, "warmup-turn", 10000, Boolean.TRUE);
            awaitCondition(() -> isTurnPathReady(aliceCh) && isTurnPathReady(bobCh), 5000, "TURN path not ready");

            MessageCapture burstCapture = new MessageCapture(3);
            bobCh.addListener(burstCapture);
            testPlatform.delayTurnDeliveryAck(turnUrlA, 1, 5500L);

            CountDownLatch completed = new CountDownLatch(3);
            AtomicBoolean failed = new AtomicBoolean(false);
            queue =
                new BlockingPacketQueue<NostrRTCChannel.PreparedPacket>(
                    new BlockingPacketQueue.PacketHandler<NostrRTCChannel.PreparedPacket>() {
                        @Override
                        public AsyncTask<Boolean> handle(NostrRTCChannel.PreparedPacket packet) {
                            return aliceCh.write(packet);
                        }

                        @Override
                        public boolean isReady() {
                            return aliceCh.isReady();
                        }

                        @Override
                        public boolean shouldPauseOnError(Throwable error) {
                            return NostrTURNChannel.isRetryableWriteFailure(error);
                        }
                    },
                    logger,
                    "Failed to send data to peer"
                );

            enqueueQueuedWrite(queue, aliceCh, "burst-1", completed, failed);
            enqueueQueuedWrite(queue, aliceCh, "burst-2", completed, failed);
            enqueueQueuedWrite(queue, aliceCh, "burst-3", completed, failed);

            assertTrue("queued TURN writes did not recover after delayed delivery_ack", completed.await(20, TimeUnit.SECONDS));
            assertFalse("queued TURN writes should not fail on delayed delivery_ack", failed.get());
            assertTrue("receiver did not observe all queued messages", burstCapture.await(2));

            assertEquals(
                3,
                burstCapture.countMessageOnTransport("burst-1", true) +
                burstCapture.countMessageOnTransport("burst-2", true) +
                burstCapture.countMessageOnTransport("burst-3", true)
            );
            assertEquals(1, burstCapture.countMessageOnTransport("burst-1", true));
            assertEquals(1, burstCapture.countMessageOnTransport("burst-2", true));
            assertEquals(1, burstCapture.countMessageOnTransport("burst-3", true));
            assertTrue(burstCapture.firstIndexOf("burst-1") < burstCapture.firstIndexOf("burst-2"));
            assertTrue(burstCapture.firstIndexOf("burst-2") < burstCapture.firstIndexOf("burst-3"));
        } finally {
            if (queue != null) {
                queue.close();
            }
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testTurnDeliveryAckWaitsForSlowListenerCompletion() throws Exception {
        String aliceSession = "alice-turn-slow-listener";
        String bobSession = "bob-turn-slow-listener";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        CountDownLatch releaseSlowListener = new CountDownLatch(1);
        try {
            connect(alice, bob);
            NostrRTCChannel aliceCh = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobCh = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture warmupCapture = new MessageCapture(1);
            bobCh.addListener(warmupCapture);
            sendUntilMessageReceived(aliceCh, warmupCapture, "warmup-turn", 10000, Boolean.TRUE);
            awaitCondition(() -> isTurnPathReady(aliceCh) && isTurnPathReady(bobCh), 5000, "TURN path not ready");

            String slowMessage = "slow-listener-turn";
            CountDownLatch slowListenerEntered = new CountDownLatch(1);
            CountDownLatch slowListenerFinished = new CountDownLatch(1);
            bobCh.addListener(
                new NostrRTCChannelListener() {
                    @Override
                    public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean turn) {
                        ByteBuffer copy = bbf.duplicate();
                        byte[] bytes = new byte[copy.remaining()];
                        copy.get(bytes);
                        if (!turn || !slowMessage.equals(new String(bytes, StandardCharsets.UTF_8))) {
                            return;
                        }
                        slowListenerEntered.countDown();
                        try {
                            releaseSlowListener.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        } finally {
                            slowListenerFinished.countDown();
                        }
                    }

                    @Override
                    public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

                    @Override
                    public void onRTCChannelClosed(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
                }
            );

            MessageCapture capture = new MessageCapture(1);
            bobCh.addListener(capture);

            AtomicReference<Boolean> writeResult = new AtomicReference<Boolean>();
            AtomicReference<Throwable> writeError = new AtomicReference<Throwable>();
            CountDownLatch writeCompleted = new CountDownLatch(1);
            aliceCh
                .write(bytes(slowMessage))
                .then(result -> {
                    writeResult.set(result);
                    writeCompleted.countDown();
                    return null;
                })
                .catchException(ex -> {
                    writeError.set(ex);
                    writeCompleted.countDown();
                });

            assertTrue("slow TURN listener was not entered", slowListenerEntered.await(5, TimeUnit.SECONDS));
            assertFalse(
                "TURN write should stay pending until listener delivery completes",
                writeCompleted.await(2, TimeUnit.SECONDS)
            );
            assertFalse(
                "message capture should still be blocked behind the slow listener",
                capture.containsMessage(slowMessage)
            );

            releaseSlowListener.countDown();

            assertTrue("slow TURN listener did not finish", slowListenerFinished.await(5, TimeUnit.SECONDS));
            assertTrue("TURN write should complete after listener delivery", writeCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, writeResult.get());
            assertEquals(null, writeError.get());
            assertTrue("receiver did not observe the message after listener release", capture.await(2));
            assertTrue(capture.containsMessageOnTransport(slowMessage, true));
        } finally {
            releaseSlowListener.countDown();
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testMultiplePeersNoCrossPoisoningAndTurnPoolSharing() throws Exception {
        String aliceSessionBob = "alice-multi-bob";
        String aliceSessionCarol = "alice-multi-carol";
        String bobSession = "bob-multi";
        String carolSession = "carol-multi";
        testPlatform.reset();
        testPlatform.setProfile(aliceSessionBob, TransportProfile.rejectRtc());
        testPlatform.setProfile(aliceSessionCarol, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(carolSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrTURNPool sharedAliceTurnPool = new NostrTURNPool(TURN_MAX_DIFF);
        SocketContext aliceToBob = newSocketContext("alice", aliceSessionBob, roomKeyPair, turnUrlA, sharedAliceTurnPool);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        SocketContext aliceToCarol = newSocketContext("alice", aliceSessionCarol, roomKeyPair, turnUrlA, sharedAliceTurnPool);
        SocketContext carol = newSocketContext("carol", carolSession, roomKeyPair, turnUrlA, null);

        try {
            connect(aliceToBob, bob);
            connect(aliceToCarol, carol);

            NostrRTCChannel ab = aliceToBob.socket.createChannel("mesh", true, true, 0, null);
            NostrRTCChannel abAux = aliceToBob.socket.createChannel("aux", true, true, 0, null);
            NostrRTCChannel bMesh = bob.socket.createChannel("mesh", true, true, 0, null);
            NostrRTCChannel bAux = bob.socket.createChannel("aux", true, true, 0, null);
            NostrRTCChannel ac = aliceToCarol.socket.createChannel("mesh", true, true, 0, null);
            NostrRTCChannel cMesh = carol.socket.createChannel("mesh", true, true, 0, null);

            MessageCapture bMeshCapture = new MessageCapture(1);
            MessageCapture bAuxCapture = new MessageCapture(1);
            MessageCapture cMeshCapture = new MessageCapture(1);
            bMesh.addListener(bMeshCapture);
            bAux.addListener(bAuxCapture);
            cMesh.addListener(cMeshCapture);

            awaitCondition(
                new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        return (
                            isTurnPathReady(ab) &&
                            isTurnPathReady(abAux) &&
                            isTurnPathReady(bMesh) &&
                            isTurnPathReady(bAux) &&
                            isTurnPathReady(ac) &&
                            isTurnPathReady(cMesh)
                        );
                    }
                },
                20000,
                "TURN paths did not become ready for multi-peer send phase"
            );

            sendUntilMessageReceived(ab, bMeshCapture, "to-bob-mesh", 20000);
            sendUntilMessageReceived(abAux, bAuxCapture, "to-bob-aux", 20000);
            sendUntilMessageReceived(ac, cMeshCapture, "to-carol-mesh", 20000);

            assertTrue(bMeshCapture.await(2));
            assertTrue(bAuxCapture.await(2));
            assertTrue(cMeshCapture.await(2));
            assertEquals("to-bob-mesh", bMeshCapture.messages.get(0));
            assertEquals("to-bob-aux", bAuxCapture.messages.get(0));
            assertEquals("to-carol-mesh", cMeshCapture.messages.get(0));

            assertTrue(bMeshCapture.turnFlags.get(0).booleanValue());
            assertTrue(bAuxCapture.turnFlags.get(0).booleanValue());
            assertTrue(cMeshCapture.turnFlags.get(0).booleanValue());

            assertEquals(1, getTurnTransportCount(sharedAliceTurnPool));
        } finally {
            aliceToBob.closeSocketOnly();
            aliceToCarol.closeSocketOnly();
            bob.close();
            carol.close();
            sharedAliceTurnPool.close();
            aliceToBob.closeExecutorsOnly();
            aliceToCarol.closeExecutorsOnly();
        }
    }

    @Test
    public void testDifferentReceiveAndSendTurnServers() throws Exception {
        String aliceSession = "alice-dual-turn";
        String bobSession = "bob-dual-turn";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlB, null);

        try {
            connect(alice, bob);
            NostrRTCChannel aliceCh = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobCh = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture bobCapture = new MessageCapture(1);
            MessageCapture aliceCapture = new MessageCapture(1);
            bobCh.addListener(bobCapture);
            aliceCh.addListener(aliceCapture);

            sendUntilMessageReceived(aliceCh, bobCapture, "a-to-b", 10000);
            sendUntilMessageReceived(bobCh, aliceCapture, "b-to-a", 10000);

            assertTrue(bobCapture.await(2));
            assertTrue(aliceCapture.await(2));
            assertEquals("a-to-b", bobCapture.messages.get(0));
            assertEquals("b-to-a", aliceCapture.messages.get(0));
            assertTrue(bobCapture.turnFlags.get(0).booleanValue());
            assertTrue(aliceCapture.turnFlags.get(0).booleanValue());

            assertFalse(testPlatform.getCapturedBinaryFrames(turnUrlA).isEmpty());
            assertFalse(testPlatform.getCapturedBinaryFrames(turnUrlB).isEmpty());
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testTurnFallbackFailsWhenSendOrReceiveTurnServerMissing() throws Exception {
        String aliceSession = "alice-missing-turn-counterpart";
        String bobSession = "bob-missing-turn-counterpart";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        String isolatedTurnUrl = "ws://turn-missing-" + System.nanoTime() + ".invalid/turn";
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, isolatedTurnUrl, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, null, null);

        try {
            connect(alice, bob);
            alice.socket.setForceTURN(true);
            bob.socket.setForceTURN(true);
            NostrRTCChannel aliceCh = alice.socket.createChannel("primary", true, true, 0, null);

            AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            CountDownLatch errorLatch = new CountDownLatch(1);
            aliceCh.addListener(
                new NostrRTCChannelListener() {
                    @Override
                    public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean turn) {}

                    @Override
                    public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {
                        error.compareAndSet(null, e);
                        errorLatch.countDown();
                    }

                    @Override
                    public void onRTCChannelClosed(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
                }
            );

            Boolean writeResult = NGEUtils.awaitNoThrow(aliceCh.write(bytes("should-fail")));

            assertFalse(Boolean.TRUE.equals(writeResult));
            assertTrue("missing TURN counterpart should surface a channel error", errorLatch.await(2, TimeUnit.SECONDS));
            assertTrue(error.get() instanceof IllegalStateException);
            assertEquals(
                "TURN fallback requires both sender and receiver TURN servers to be configured",
                error.get().getMessage()
            );
            assertTrue(
                "ensureTurn should fail before sending any TURN frames",
                testPlatform.getCapturedBinaryFrames(isolatedTurnUrl).isEmpty()
            );
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testTurnChannelResurrectionAfterTransportDrop() throws Exception {
        String aliceSession = "alice-turn-res";
        String bobSession = "bob-turn-res";
        testPlatform.reset();
        testPlatform.setProfile(aliceSession, TransportProfile.rejectRtc());
        testPlatform.setProfile(bobSession, TransportProfile.rejectRtc());

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext("alice", aliceSession, roomKeyPair, turnUrlA, null);
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, turnUrlA, null);
        try {
            connect(alice, bob);
            NostrRTCChannel aliceDefault = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobDefault = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture capture = new MessageCapture(2);
            bobDefault.addListener(capture);

            sendUntilMessageReceived(aliceDefault, capture, "before-drop", 10000);

            forceCloseTurnTransports(alice.turnPool);
            forceCloseTurnTransports(bob.turnPool);

            sendUntilMessageReceived(aliceDefault, capture, "after-drop", 20000);
            int beforeIdx = capture.firstIndexOf("before-drop");
            int afterIdx = capture.firstIndexOf("after-drop");
            assertTrue(beforeIdx >= 0);
            assertTrue(afterIdx > beforeIdx);
            assertTrue(capture.turnFlags.get(beforeIdx).booleanValue());
            assertTrue(capture.turnFlags.get(afterIdx).booleanValue());
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    public void testRealTurnWithInvalidStunForcesTurnPath() throws Exception {
        String aliceSession = "alice-real-turn-invalid-stun";
        String bobSession = "bob-real-turn-invalid-stun";
        String realTurnUrl = "wss://relay.ngengine.org/turn";

        testPlatform.reset();

        NostrKeyPair roomKeyPair = new NostrKeyPair();
        SocketContext alice = newSocketContext(
            "alice",
            aliceSession,
            roomKeyPair,
            realTurnUrl,
            null,
            List.of("stun:invalid.stun.ngengine.local:3478")
        );
        SocketContext bob = newSocketContext("bob", bobSession, roomKeyPair, realTurnUrl, null, Collections.emptyList());

        try {
            connect(alice, bob);

            NostrRTCChannel aliceCh = alice.socket.createChannel("primary", true, true, 0, null);
            NostrRTCChannel bobCh = bob.socket.createChannel("primary", true, true, 0, null);

            MessageCapture capture = new MessageCapture(1);
            bobCh.addListener(capture);

            sendUntilMessageReceived(aliceCh, capture, "real-turn-only", 20000, Boolean.TRUE);
            assertTrue(capture.await(2));
            assertEquals("real-turn-only", capture.messages.get(0));
            assertTrue(capture.turnFlags.get(0).booleanValue());

            assertFalse(
                "Expected binary TURN frames on real TURN endpoint",
                testPlatform.getCapturedBinaryFrames(realTurnUrl).isEmpty()
            );
        } finally {
            alice.close();
            bob.close();
        }
    }

    private static SocketContext newSocketContext(
        String user,
        String sessionId,
        NostrKeyPair roomKeyPair,
        String turnServer,
        NostrTURNPool overridePool
    ) {
        return newSocketContext(user, sessionId, roomKeyPair, turnServer, overridePool, Collections.emptyList());
    }

    private static SocketContext newSocketContext(
        String user,
        String sessionId,
        NostrKeyPair roomKeyPair,
        String turnServer,
        NostrTURNPool overridePool,
        Collection<String> stuns
    ) {
        NostrTURNPool pool = overridePool != null ? overridePool : new NostrTURNPool(TURN_MAX_DIFF);
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("test-" + user + "-" + sessionId);
        NostrRTCLocalPeer localPeer = newLocalPeer(user, sessionId, roomKeyPair, turnServer, stuns);
        return new SocketContext(null, localPeer, roomKeyPair, executor, pool, overridePool != null);
    }

    @SuppressWarnings("unused")
    private static NostrRTCLocalPeer newLocalPeer(String user, String sessionId, NostrKeyPair roomKeyPair, String turnServer) {
        return newLocalPeer(user, sessionId, roomKeyPair, turnServer, Collections.emptyList());
    }

    private static NostrRTCLocalPeer newLocalPeer(
        String user,
        String sessionId,
        NostrKeyPair roomKeyPair,
        String turnServer,
        Collection<String> stuns
    ) {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        return new NostrRTCLocalPeer(signer, stuns, APP_ID, PROTOCOL_ID, sessionId, roomKeyPair, turnServer);
    }

    private static void connect(SocketContext a, SocketContext b) throws Exception {
        a.closeSocketOnly();
        b.closeSocketOnly();
        a.socket =
            new NostrRTCSocket(
                a.executor,
                socketRemotePeerFromLocal(b.localPeer, a.roomKeyPair),
                a.roomKeyPair,
                a.localPeer,
                RTCSettings.DEFAULT,
                null,
                a.turnPool
            );
        b.socket =
            new NostrRTCSocket(
                b.executor,
                socketRemotePeerFromLocal(a.localPeer, b.roomKeyPair),
                b.roomKeyPair,
                b.localPeer,
                RTCSettings.DEFAULT,
                null,
                b.turnPool
            );

        NostrPool poolA = new NostrPool();
        NostrPool poolB = new NostrPool();
        NostrRTCSignaling signalingA = null;
        NostrRTCSignaling signalingB = null;
        CountDownLatch connected = new CountDownLatch(1);
        AtomicBoolean answerApplied = new AtomicBoolean(false);
        AtomicReference<String> cachedAnswerSdp = new AtomicReference<String>();
        AtomicBoolean routeAReceivedFromB = new AtomicBoolean(false);
        AtomicBoolean routeBReceivedFromA = new AtomicBoolean(false);
        try {
            for (String relayUrl : REAL_RELAYS) {
                attachRelay(poolA, relayUrl);
                attachRelay(poolB, relayUrl);
            }

            String sharedRelay = waitForSharedConnectedRelay(poolA, poolB, 20_000);
            assertNotNull("No shared connected relay for peer A/B", sharedRelay);

            signalingA = new NostrRTCSignaling(RTCSettings.DEFAULT, APP_ID, PROTOCOL_ID, a.localPeer, a.roomKeyPair, poolA);
            signalingB = new NostrRTCSignaling(RTCSettings.DEFAULT, APP_ID, PROTOCOL_ID, b.localPeer, b.roomKeyPair, poolB);

            final NostrRTCSignaling signalingAFinal = signalingA;
            final NostrRTCSignaling signalingBFinal = signalingB;
            signalingB.addListener(
                new NostrRTCSignaling.Listener() {
                    @Override
                    public void onAddAnnounce(org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal announce) {}

                    @Override
                    public void onUpdateAnnounce(org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal announce) {}

                    @Override
                    public void onRemoveAnnounce(
                        org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal announce,
                        NostrRTCSignaling.Listener.RemoveReason reason
                    ) {}

                    @Override
                    public void onReceiveOffer(NostrRTCOfferSignal offer) {
                        if (!a.localPeer.getPubkey().equals(offer.getPeer().getPubkey())) {
                            return;
                        }
                        String existing = cachedAnswerSdp.get();
                        if (existing != null) {
                            NGEUtils.awaitNoThrow(signalingBFinal.sendAnswer(existing, a.localPeer.getPubkey()));
                            return;
                        }
                        NostrRTCAnswerSignal answer = NGEUtils.awaitNoThrow(b.socket.connect(offer));
                        if (answer != null) {
                            cachedAnswerSdp.compareAndSet(null, answer.getSdp());
                            NGEUtils.awaitNoThrow(signalingBFinal.sendAnswer(answer.getSdp(), a.localPeer.getPubkey()));
                        }
                    }

                    @Override
                    public void onReceiveAnswer(NostrRTCAnswerSignal answer) {}

                    @Override
                    public void onReceiveCandidates(NostrRTCRouteSignal candidate) {
                        if (a.localPeer.getPubkey().equals(candidate.getPeer().getPubkey())) {
                            routeBReceivedFromA.set(true);
                            b.socket.mergeRemoteRTCIceCandidate(candidate);
                        }
                    }
                }
            );

            signalingA.addListener(
                new NostrRTCSignaling.Listener() {
                    @Override
                    public void onAddAnnounce(org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal announce) {}

                    @Override
                    public void onUpdateAnnounce(org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal announce) {}

                    @Override
                    public void onRemoveAnnounce(
                        org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal announce,
                        NostrRTCSignaling.Listener.RemoveReason reason
                    ) {}

                    @Override
                    public void onReceiveOffer(NostrRTCOfferSignal offer) {}

                    @Override
                    public void onReceiveAnswer(NostrRTCAnswerSignal answer) {
                        if (!b.localPeer.getPubkey().equals(answer.getPeer().getPubkey())) {
                            return;
                        }
                        if (answerApplied.compareAndSet(false, true)) {
                            NGEUtils.awaitNoThrow(a.socket.connect(answer));
                            connected.countDown();
                        }
                    }

                    @Override
                    public void onReceiveCandidates(NostrRTCRouteSignal candidate) {
                        if (b.localPeer.getPubkey().equals(candidate.getPeer().getPubkey())) {
                            routeAReceivedFromB.set(true);
                            a.socket.mergeRemoteRTCIceCandidate(candidate);
                        }
                    }
                }
            );

            NGEUtils.awaitNoThrow(signalingA.start(true));
            NGEUtils.awaitNoThrow(signalingB.start(true));

            a.socket.addListener(
                new NostrRTCSocketListener() {
                    @Override
                    public void onRTCSocketRouteUpdate(
                        NostrRTCSocket socket,
                        Collection<RTCTransportIceCandidate> candidates,
                        String turnServer
                    ) {
                        try {
                            NGEUtils.awaitNoThrow(signalingAFinal.sendRoutes(candidates, turnServer, b.localPeer.getPubkey()));
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onRTCSocketClose(NostrRTCSocket socket) {}

                    @Override
                    public void onRTCChannelReady(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCChannel(NostrRTCChannel channel) {}
                }
            );

            b.socket.addListener(
                new NostrRTCSocketListener() {
                    @Override
                    public void onRTCSocketRouteUpdate(
                        NostrRTCSocket socket,
                        Collection<RTCTransportIceCandidate> candidates,
                        String turnServer
                    ) {
                        try {
                            NGEUtils.awaitNoThrow(signalingBFinal.sendRoutes(candidates, turnServer, a.localPeer.getPubkey()));
                        } catch (Exception ignored) {}
                    }

                    @Override
                    public void onRTCSocketClose(NostrRTCSocket socket) {}

                    @Override
                    public void onRTCChannelReady(NostrRTCChannel channel) {}

                    @Override
                    public void onRTCChannel(NostrRTCChannel channel) {}
                }
            );

            NostrRTCOfferSignal offer = NGEUtils.awaitNoThrow(a.socket.listen());
            assertNotNull(offer);
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                NGEUtils.awaitNoThrow(signalingA.sendOffer(offer.getOfferString(), b.localPeer.getPubkey()));
                if (connected.await(2, TimeUnit.SECONDS)) {
                    break;
                }
            }
            assertTrue("Timed out waiting for relay-delivered answer", connected.getCount() == 0L);
            awaitCondition(
                new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        boolean aReady = a.socket.isRTCConnected() || routeAReceivedFromB.get();
                        boolean bReady = b.socket.isRTCConnected() || routeBReceivedFromA.get();
                        return aReady && bReady;
                    }
                },
                10000,
                "Timed out waiting for route exchange after answer"
            );
        } finally {
            if (signalingA != null) {
                signalingA.close();
            }
            if (signalingB != null) {
                signalingB.close();
            }
            for (NostrRelay relay : poolA.getRelays()) {
                relay.disconnect("test-complete");
            }
            for (NostrRelay relay : poolB.getRelays()) {
                relay.disconnect("test-complete");
            }
        }
    }

    private static void attachRelay(NostrPool pool, String relayUrl) {
        NostrRelay relay = new NostrRelay(relayUrl);
        relay.setAutoReconnect(false);
        pool.addRelay(relay);
        relay.connect();
    }

    private static String waitForSharedConnectedRelay(NostrPool poolA, NostrPool poolB, long timeoutMs)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Set<String> connectedA = connectedRelayUrls(poolA);
            Set<String> connectedB = connectedRelayUrls(poolB);
            for (String relay : connectedA) {
                if (connectedB.contains(relay)) {
                    return relay;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static Set<String> connectedRelayUrls(NostrPool pool) {
        Set<String> connected = new HashSet<String>();
        for (NostrRelay relay : pool.getRelays()) {
            if (relay.isConnected()) {
                connected.add(relay.getUrl());
            }
        }
        return connected;
    }

    @SuppressWarnings("unused")
    private static void connect(NostrRTCSocket a, NostrRTCSocket b) {
        NostrRTCOfferSignal offer = NGEUtils.awaitNoThrow(a.listen());
        assertNotNull(offer);
        NostrRTCAnswerSignal answer = NGEUtils.awaitNoThrow(b.connect(offer));
        assertNotNull(answer);
        NGEUtils.awaitNoThrow(a.connect(answer));
    }

    private static NGEPlatform getInstalledPlatform() throws Exception {
        java.lang.reflect.Field field = NGEPlatform.class.getDeclaredField("platform");
        field.setAccessible(true);
        return (NGEPlatform) field.get(null);
    }

    private static void installPlatform(NGEPlatform platform) throws Exception {
        java.lang.reflect.Field field = NGEPlatform.class.getDeclaredField("platform");
        field.setAccessible(true);
        field.set(null, platform);
    }

    private static ByteBuffer bytes(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMs, String error) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(error);
    }

    private static int findFreePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static void sendUntilMessageReceived(
        NostrRTCChannel channel,
        MessageCapture capture,
        String message,
        long timeoutMs
    ) throws Exception {
        sendUntilMessageReceived(channel, capture, message, timeoutMs, null);
    }

    private static void sendUntilMessageReceived(
        NostrRTCChannel channel,
        MessageCapture capture,
        String message,
        long timeoutMs,
        Boolean expectedTurn
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            channel.write(bytes(message));
            if (expectedTurn == null && capture.containsMessage(message)) {
                return;
            }
            if (expectedTurn != null && capture.containsMessageOnTransport(message, expectedTurn.booleanValue())) {
                return;
            }
            Thread.sleep(150);
        }
        if (expectedTurn == null) {
            throw new AssertionError("Message not delivered within timeout: " + message);
        }
        throw new AssertionError(
            "Message not delivered within timeout on expected transport (" + expectedTurn + "): " + message
        );
    }

    private static void triggerBestEffortRtcUpgradeAttempt(SocketContext initiator, SocketContext responder) {
        try {
            initiator.socket.prepareRtcTransportAttempt();
            NostrRTCOfferSignal offer = NGEUtils.awaitNoThrow(initiator.socket.listen());
            if (offer == null) {
                return;
            }
            NostrRTCAnswerSignal answer = NGEUtils.awaitNoThrow(responder.socket.connect(offer));
            if (answer != null) {
                NGEUtils.awaitNoThrow(initiator.socket.connect(answer));
            }
        } catch (Throwable ignored) {
            // Best effort only: test asserts continuity of TURN delivery regardless of upgrade result.
        }
    }

    private static void enqueueQueuedWrite(
        BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue,
        NostrRTCChannel channel,
        String message,
        CountDownLatch completed,
        AtomicBoolean failed
    ) {
        queue.enqueue(
            channel.prepareOutgoingPacket(bytes(message)),
            ignored -> completed.countDown(),
            error -> failed.set(true)
        );
    }

    private static boolean isTurnPathReady(NostrRTCChannel channel) {
        try {
            java.lang.reflect.Field turnSendField = NostrRTCChannel.class.getDeclaredField("turnSend");
            java.lang.reflect.Field turnReceiveField = NostrRTCChannel.class.getDeclaredField("turnReceive");
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
        if (!(turnChannelObj instanceof org.ngengine.nostr4j.rtc.NostrTURNChannel)) {
            return false;
        }
        return ((org.ngengine.nostr4j.rtc.NostrTURNChannel) turnChannelObj).isReady();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean found = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return true;
            }
        }
        return false;
    }

    private static int getTurnTransportCount(NostrTURNPool pool) throws Exception {
        java.lang.reflect.Field transportsField = NostrTURNPool.class.getDeclaredField("transports");
        transportsField.setAccessible(true);
        Map<?, ?> transports = (Map<?, ?>) transportsField.get(pool);
        return transports.size();
    }

    private static void forceCloseTurnTransports(NostrTURNPool pool) throws Exception {
        java.lang.reflect.Field transportsField = NostrTURNPool.class.getDeclaredField("transports");
        transportsField.setAccessible(true);
        Map<?, ?> transports = (Map<?, ?>) transportsField.get(pool);
        for (Object entryObj : transports.values()) {
            Object transportObj = entryObj;
            // Support both direct transport entries and async wrappers.
            if (entryObj instanceof org.ngengine.platform.AsyncTask) {
                transportObj = org.ngengine.platform.NGEUtils.awaitNoThrow((org.ngengine.platform.AsyncTask<?>) entryObj);
            }
            if (transportObj == null) {
                continue;
            }
            java.lang.reflect.Method closeMethod = transportObj.getClass().getDeclaredMethod("close", String.class);
            closeMethod.setAccessible(true);
            closeMethod.invoke(transportObj, "test-forced-close");
        }
    }

    private static ByteBuffer frameSinglePacket(NostrRTCChannel channel, NostrRTCChannel.PreparedPacket packet)
        throws Exception {
        java.lang.reflect.Method encodePacketFragments =
            NostrRTCChannel.class.getDeclaredMethod("encodePacketFragments", NostrRTCChannel.PreparedPacket.class, int.class);
        encodePacketFragments.setAccessible(true);
        ByteBuffer[] framed = (ByteBuffer[]) encodePacketFragments.invoke(channel, packet, Integer.MAX_VALUE / 4);
        assertEquals("Expected single fragment in test helper", 1, framed.length);
        return framed[0].asReadOnlyBuffer();
    }

    private static final class SocketContext {

        private NostrRTCSocket socket;
        private NostrRTCLocalPeer localPeer;
        private final NostrKeyPair roomKeyPair;
        private final AsyncExecutor executor;
        private final NostrTURNPool turnPool;
        private final boolean sharedTurnPool;

        private SocketContext(
            NostrRTCSocket socket,
            NostrRTCLocalPeer localPeer,
            NostrKeyPair roomKeyPair,
            AsyncExecutor executor,
            NostrTURNPool turnPool,
            boolean sharedTurnPool
        ) {
            this.socket = socket;
            this.localPeer = localPeer;
            this.roomKeyPair = roomKeyPair;
            this.executor = executor;
            this.turnPool = turnPool;
            this.sharedTurnPool = sharedTurnPool;
        }

        private void close() {
            closeSocketOnly();
            if (!sharedTurnPool) {
                turnPool.close();
            }
            closeExecutorsOnly();
        }

        private void closeSocketOnly() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception ignored) {}
        }

        private void closeExecutorsOnly() {
            try {
                executor.close();
            } catch (Exception ignored) {}
        }
    }

    private static NostrRTCPeer socketRemotePeerFromLocal(NostrRTCLocalPeer localPeer, NostrKeyPair roomKeyPair) {
        return new NostrRTCPeer(
            localPeer.getPubkey(),
            localPeer.getApplicationId(),
            localPeer.getProtocolId(),
            localPeer.getSessionId(),
            roomKeyPair.getPublicKey(),
            localPeer.getTurnServer()
        );
    }

    private static final class MessageCapture implements NostrRTCChannelListener {

        private final CountDownLatch latch;
        private final List<String> messages = new CopyOnWriteArrayList<String>();
        private final List<Boolean> turnFlags = new CopyOnWriteArrayList<Boolean>();

        private MessageCapture(int expectedMessages) {
            this.latch = new CountDownLatch(expectedMessages);
        }

        private boolean await(int timeoutSeconds) throws InterruptedException {
            return latch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        private boolean containsMessage(String message) {
            return messages.contains(message);
        }

        private int firstIndexOf(String message) {
            return messages.indexOf(message);
        }

        private boolean containsMessageOnTransport(String message, boolean turn) {
            int size = Math.min(messages.size(), turnFlags.size());
            for (int i = 0; i < size; i++) {
                if (message.equals(messages.get(i)) && turnFlags.get(i).booleanValue() == turn) {
                    return true;
                }
            }
            return false;
        }

        private int countMessageOnTransport(String message, boolean turn) {
            int matches = 0;
            int size = Math.min(messages.size(), turnFlags.size());
            for (int i = 0; i < size; i++) {
                if (message.equals(messages.get(i)) && turnFlags.get(i).booleanValue() == turn) {
                    matches++;
                }
            }
            return matches;
        }

        @Override
        public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean turn) {
            ByteBuffer copy = bbf.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            messages.add(new String(bytes, StandardCharsets.UTF_8));
            turnFlags.add(Boolean.valueOf(turn));
            latch.countDown();
        }

        @Override
        public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {}

        @Override
        public void onRTCChannelClosed(NostrRTCChannel channel) {}

        @Override
        public void onRTCBufferedAmountLow(NostrRTCChannel channel) {}
    }

    private static final class TransportProfile {

        private final boolean rejectRtc;
        private final boolean transientDisconnect;
        private final long reconnectDelayMs;

        private TransportProfile(boolean rejectRtc, boolean transientDisconnect, long reconnectDelayMs) {
            this.rejectRtc = rejectRtc;
            this.transientDisconnect = transientDisconnect;
            this.reconnectDelayMs = reconnectDelayMs;
        }

        private static TransportProfile directStable() {
            return new TransportProfile(false, false, 0L);
        }

        private static TransportProfile rejectRtc() {
            return new TransportProfile(true, false, 0L);
        }

        private static TransportProfile transientDrop(long reconnectDelayMs) {
            return new TransportProfile(false, true, reconnectDelayMs);
        }
    }

    private static final class TestPlatform extends JVMAsyncPlatform {

        private final Map<String, TransportProfile> profilesByConnId = new ConcurrentHashMap<String, TransportProfile>();
        private final Map<String, FakeRTCTransport> rtcByConnId = new ConcurrentHashMap<String, FakeRTCTransport>();
        private final Map<String, List<byte[]>> binaryFramesByUrl = new ConcurrentHashMap<String, List<byte[]>>();
        private final Map<String, DelayedDeliveryAck> delayedDeliveryAcksByUrl =
            new ConcurrentHashMap<String, DelayedDeliveryAck>();
        private final Map<String, Map<String, AtomicInteger>> droppedIncomingTurnTypesByUrl =
            new ConcurrentHashMap<String, Map<String, AtomicInteger>>();
        private final AsyncExecutor delayedTurnFrameExecutor = NGEUtils
            .getPlatform()
            .newAsyncExecutor("integration-delayed-turn-frame");

        private void reset() {
            profilesByConnId.clear();
            rtcByConnId.clear();
            binaryFramesByUrl.clear();
            delayedDeliveryAcksByUrl.clear();
            droppedIncomingTurnTypesByUrl.clear();
        }

        private void setProfile(String connId, TransportProfile profile) {
            profilesByConnId.put(connId, profile);
        }

        private TransportProfile profileFor(String connId) {
            TransportProfile profile = profilesByConnId.get(connId);
            return profile != null ? profile : TransportProfile.directStable();
        }

        @Override
        public RTCTransport newRTCTransport(Duration timeout, String connId, Collection<String> stunServers) {
            if (!profilesByConnId.containsKey(connId) && hasInvalidStun(stunServers)) {
                setProfile(connId, TransportProfile.rejectRtc());
            }
            FakeRTCTransport transport = new FakeRTCTransport(this, connId);
            rtcByConnId.put(connId, transport);
            return transport;
        }

        private static boolean hasInvalidStun(Collection<String> stunServers) {
            if (stunServers == null || stunServers.isEmpty()) {
                return false;
            }
            for (String stun : stunServers) {
                if (stun != null && stun.toLowerCase().contains("invalid")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public WebsocketTransport newTransport() {
            return new CapturingWebsocketTransport(super.newTransport(), this);
        }

        private List<byte[]> getCapturedBinaryFrames(String url) {
            List<byte[]> frames = binaryFramesByUrl.get(url);
            if (frames == null) {
                return Collections.emptyList();
            }
            return new ArrayList<byte[]>(frames);
        }

        private void captureBinary(String url, ByteBuffer data) {
            if (url == null) {
                return;
            }
            byte[] copy = new byte[data.remaining()];
            data.duplicate().get(copy);
            binaryFramesByUrl.computeIfAbsent(url, key -> new CopyOnWriteArrayList<byte[]>()).add(copy);
        }

        private void delayTurnDeliveryAck(String url, int count, long delayMs) {
            delayedDeliveryAcksByUrl.put(url, new DelayedDeliveryAck(count, delayMs));
        }

        private void dropIncomingTurnFrameType(String url, String type, int count) {
            if (url == null || type == null || type.isEmpty() || count <= 0) {
                return;
            }
            droppedIncomingTurnTypesByUrl
                .computeIfAbsent(url, key -> new ConcurrentHashMap<String, AtomicInteger>())
                .put(type, new AtomicInteger(count));
        }

        private AsyncTask<Void> maybeDelayTurnDeliveryAck(String url, ByteBuffer payload, WebsocketTransport delegate) {
            if (url == null) {
                return null;
            }
            DelayedDeliveryAck delay = delayedDeliveryAcksByUrl.get(url);
            if (delay == null || !delay.tryAcquire()) {
                return null;
            }
            String type = turnFrameType(payload);
            if (!"delivery_ack".equals(type)) {
                delay.releaseUnused();
                return null;
            }

            ByteBuffer delayedPayload = copyBuffer(payload);
            return AsyncTask.create((resolve, reject) -> {
                delayedTurnFrameExecutor.runLater(
                    () -> {
                        delegate
                            .sendBinary(delayedPayload)
                            .then(v -> {
                                resolve.accept(null);
                                return null;
                            })
                            .catchException(ex -> {
                                reject.accept(ex);
                            });
                        return null;
                    },
                    delay.delayMs,
                    TimeUnit.MILLISECONDS
                );
            });
        }

        private boolean shouldDropIncomingTurnFrame(String url, ByteBuffer payload) {
            if (url == null) {
                return false;
            }
            String type = turnFrameType(payload);
            if (type == null || type.isEmpty()) {
                return false;
            }
            Map<String, AtomicInteger> byType = droppedIncomingTurnTypesByUrl.get(url);
            if (byType == null) {
                return false;
            }
            AtomicInteger remaining = byType.get(type);
            if (remaining == null) {
                return false;
            }
            while (true) {
                int current = remaining.get();
                if (current <= 0) {
                    return false;
                }
                if (remaining.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        private static String turnFrameType(ByteBuffer payload) {
            try {
                SignedNostrEvent header = org.ngengine.nostr4j.rtc.turn.NostrTURNCodec.decodeHeader(payload.asReadOnlyBuffer());
                return header.getFirstTagFirstValue("t");
            } catch (Exception ignored) {
                return null;
            }
        }

        private static ByteBuffer copyBuffer(ByteBuffer payload) {
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload.duplicate());
            copy.flip();
            return copy.asReadOnlyBuffer();
        }

        private void tryLink(FakeRTCTransport left, FakeRTCTransport right) {
            if (left == null || right == null) {
                return;
            }
            if (left.isLinkedWith(right) && left.connected.get() && right.connected.get()) {
                return;
            }
            if (!Objects.equals(left.remoteConnId, right.connId)) {
                return;
            }
            if (!Objects.equals(right.remoteConnId, left.connId)) {
                return;
            }

            TransportProfile leftProfile = profileFor(left.connId);
            TransportProfile rightProfile = profileFor(right.connId);
            if (leftProfile.rejectRtc || rightProfile.rejectRtc) {
                left.notifyDisconnected("simulated-rtc-unreachable");
                right.notifyDisconnected("simulated-rtc-unreachable");
                return;
            }

            left.linkTo(right);
            right.linkTo(left);
            left.notifyConnectedOnce();
            right.notifyConnectedOnce();

            if (leftProfile.transientDisconnect || rightProfile.transientDisconnect) {
                long delay = Math.max(leftProfile.reconnectDelayMs, rightProfile.reconnectDelayMs);
                this.newAsyncExecutor("transient-link")
                    .runLater(
                        () -> {
                            left.notifyChannelClosed("simulated-drop");
                            right.notifyChannelClosed("simulated-drop");
                            left.notifyDisconnected("simulated-drop");
                            right.notifyDisconnected("simulated-drop");
                            this.newAsyncExecutor("transient-recover")
                                .runLater(
                                    () -> {
                                        left.notifyConnectedOnce();
                                        right.notifyConnectedOnce();
                                        return null;
                                    },
                                    delay,
                                    TimeUnit.MILLISECONDS
                                );
                            return null;
                        },
                        350,
                        TimeUnit.MILLISECONDS
                    );
            }
        }
    }

    private static final class CapturingWebsocketTransport implements WebsocketTransport {

        private final WebsocketTransport delegate;
        private final TestPlatform platform;
        private final Map<WebsocketTransportListener, WebsocketTransportListener> wrappedListeners =
            new ConcurrentHashMap<WebsocketTransportListener, WebsocketTransportListener>();
        private volatile String currentUrl;

        private CapturingWebsocketTransport(WebsocketTransport delegate, TestPlatform platform) {
            this.delegate = delegate;
            this.platform = platform;
        }

        @Override
        public AsyncTask<Void> close(String reason) {
            return delegate.close(reason);
        }

        @Override
        public AsyncTask<Void> connect(String url) {
            this.currentUrl = url;
            return delegate.connect(url);
        }

        @Override
        public AsyncTask<Void> send(String message) {
            return delegate.send(message);
        }

        @Override
        public AsyncTask<Void> sendBinary(ByteBuffer payload) {
            platform.captureBinary(currentUrl, payload.duplicate());
            AsyncTask<Void> delayed = platform.maybeDelayTurnDeliveryAck(currentUrl, payload.duplicate(), delegate);
            if (delayed != null) {
                return delayed;
            }
            return delegate.sendBinary(payload);
        }

        @Override
        public void addListener(WebsocketTransportListener listener) {
            WebsocketTransportListener wrapped = new WebsocketTransportListener() {
                @Override
                public void onConnectionClosedByServer(String reason) {
                    listener.onConnectionClosedByServer(reason);
                }

                @Override
                public void onConnectionOpen() {
                    listener.onConnectionOpen();
                }

                @Override
                public void onConnectionMessage(String msg) {
                    listener.onConnectionMessage(msg);
                }

                @Override
                public void onConnectionBinaryMessage(ByteBuffer msg) {
                    if (platform.shouldDropIncomingTurnFrame(currentUrl, msg.asReadOnlyBuffer())) {
                        return;
                    }
                    listener.onConnectionBinaryMessage(msg);
                }

                @Override
                public void onConnectionClosedByClient(String reason) {
                    listener.onConnectionClosedByClient(reason);
                }

                @Override
                public void onConnectionError(Throwable e) {
                    listener.onConnectionError(e);
                }
            };
            wrappedListeners.put(listener, wrapped);
            delegate.addListener(wrapped);
        }

        @Override
        public void removeListener(WebsocketTransportListener listener) {
            WebsocketTransportListener wrapped = wrappedListeners.remove(listener);
            delegate.removeListener(wrapped == null ? listener : wrapped);
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public void setMaxMessageSize(int maxMessageSize) {}

        @Override
        public int getMaxMessageSize() {
            return 1024 * 1024 * 10;
        }
    }

    private static int turnLogicalSocketCount(TurnServer server) {
        if (server == null) {
            return 0;
        }
        try {
            Field field = TurnServer.class.getDeclaredField("logicalSockets");
            field.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) field.get(server);
            return map == null ? 0 : map.size();
        } catch (Exception e) {
            throw new RuntimeException("Unable to inspect TURN logical socket registry", e);
        }
    }

    private static final class DelayedDeliveryAck {

        private final AtomicInteger remaining;
        private final long delayMs;

        private DelayedDeliveryAck(int remaining, long delayMs) {
            this.remaining = new AtomicInteger(remaining);
            this.delayMs = delayMs;
        }

        private boolean tryAcquire() {
            while (true) {
                int current = remaining.get();
                if (current <= 0) {
                    return false;
                }
                if (remaining.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        private void releaseUnused() {
            remaining.incrementAndGet();
        }
    }

    private static final class FakeRTCTransport implements RTCTransport {

        private final TestPlatform platform;
        private final String connId;
        private final List<RTCTransportListener> listeners = new CopyOnWriteArrayList<RTCTransportListener>();
        private final Map<String, FakeRTCDataChannel> channels = new ConcurrentHashMap<String, FakeRTCDataChannel>();
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private volatile FakeRTCTransport linkedPeer;
        private volatile String remoteConnId;
        private volatile boolean connectedSignaled;

        private FakeRTCTransport(TestPlatform platform, String connId) {
            this.platform = platform;
            this.connId = connId;
        }

        @Override
        public void start(Duration timeout, AsyncExecutor executor, String connId, Collection<String> stunServers) {}

        @Override
        public AsyncTask<String> listen() {
            notifyCandidate();
            return AsyncTask.completed("offer:" + connId);
        }

        @Override
        public AsyncTask<String> connect(String offerOrAnswer) {
            notifyCandidate();
            if (offerOrAnswer.startsWith("offer:")) {
                this.remoteConnId = offerOrAnswer.substring("offer:".length());
                FakeRTCTransport remote = platform.rtcByConnId.get(this.remoteConnId);
                platform.tryLink(this, remote);
                return AsyncTask.completed("answer:" + this.connId + ":" + this.remoteConnId);
            }
            if (offerOrAnswer.startsWith("answer:")) {
                String[] parts = offerOrAnswer.split(":");
                if (parts.length < 3) {
                    return AsyncTask.failed(new IllegalArgumentException("Malformed answer"));
                }
                this.remoteConnId = parts[1];
                FakeRTCTransport remote = platform.rtcByConnId.get(this.remoteConnId);
                platform.tryLink(this, remote);
                return AsyncTask.completed(null);
            }
            return AsyncTask.failed(new IllegalArgumentException("Unsupported offer/answer: " + offerOrAnswer));
        }

        @Override
        public AsyncTask<RTCDataChannel> createDataChannel(
            String name,
            String protocol,
            boolean ordered,
            boolean reliable,
            int maxRetransmits,
            Duration maxPacketLifeTime
        ) {
            FakeRTCTransport peer = linkedPeer;
            if (peer == null || !connected.get()) {
                return AsyncTask.failed(new IllegalStateException("Peer not connected"));
            }

            FakeRTCDataChannel existing = channels.get(name);
            if (existing != null) {
                return AsyncTask.completed(existing);
            }

            FakeRTCDataChannel local = new FakeRTCDataChannel(
                this,
                name,
                protocol,
                ordered,
                reliable,
                maxRetransmits,
                maxPacketLifeTime
            );
            FakeRTCDataChannel remote = new FakeRTCDataChannel(
                peer,
                name,
                protocol,
                ordered,
                reliable,
                maxRetransmits,
                maxPacketLifeTime
            );
            local.setPeer(remote);
            remote.setPeer(local);
            channels.put(name, local);
            peer.channels.put(name, remote);
            notifyChannelReady(local);
            peer.notifyChannelReady(remote);
            return AsyncTask.completed(local);
        }

        @Override
        public void addRemoteIceCandidates(Collection<RTCTransportIceCandidate> candidates) {}

        @Override
        public void addListener(RTCTransportListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(RTCTransportListener listener) {
            listeners.remove(listener);
        }

        @Override
        public RTCDataChannel getDataChannel(String name) {
            return channels.get(name);
        }

        @Override
        public String getName() {
            return connId;
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public void close() {
            notifyChannelClosed("closed");
            notifyDisconnected("closed");
        }

        private boolean isLinkedWith(FakeRTCTransport other) {
            return linkedPeer == other;
        }

        private void linkTo(FakeRTCTransport peer) {
            this.linkedPeer = peer;
            this.connected.set(true);
        }

        private void notifyCandidate() {
            RTCTransportIceCandidate candidate = new RTCTransportIceCandidate("candidate-" + connId, "0");
            for (RTCTransportListener listener : listeners) {
                listener.onLocalRTCIceCandidate(candidate);
            }
        }

        private void notifyConnectedOnce() {
            connected.set(true);
            if (connectedSignaled) {
                return;
            }
            connectedSignaled = true;
            for (RTCTransportListener listener : listeners) {
                listener.onRTCConnected();
            }
        }

        private void notifyDisconnected(String reason) {
            connected.set(false);
            connectedSignaled = false;
            for (RTCTransportListener listener : listeners) {
                listener.onRTCDisconnected(reason);
            }
        }

        private void notifyChannelReady(FakeRTCDataChannel channel) {
            for (RTCTransportListener listener : listeners) {
                listener.onRTCChannelReady(channel);
            }
        }

        private void notifyChannelClosed(String reason) {
            for (FakeRTCDataChannel channel : channels.values()) {
                channel.closed = true;
                for (RTCTransportListener listener : listeners) {
                    listener.onRTCChannelClosed(channel);
                }
            }
            channels.clear();
        }

        private void receive(FakeRTCDataChannel channel, ByteBuffer payload) {
            for (RTCTransportListener listener : listeners) {
                listener.onRTCBinaryMessage(channel, payload);
            }
        }
    }

    private static final class FakeRTCDataChannel extends RTCDataChannel {

        private final FakeRTCTransport owner;
        private volatile FakeRTCDataChannel peer;
        private volatile boolean closed;
        private volatile int lowThreshold = 0;

        private FakeRTCDataChannel(
            FakeRTCTransport owner,
            String name,
            String protocol,
            boolean ordered,
            boolean reliable,
            int maxRetransmits,
            Duration maxPacketLifeTime
        ) {
            super(name, protocol, ordered, reliable, maxRetransmits, maxPacketLifeTime);
            this.owner = owner;
        }

        private void setPeer(FakeRTCDataChannel peer) {
            this.peer = peer;
        }

        @Override
        public AsyncTask<RTCDataChannel> ready() {
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<Void> write(ByteBuffer data) {
            if (closed || peer == null || peer.closed) {
                return AsyncTask.failed(new IllegalStateException("DataChannel is closed"));
            }
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data.duplicate());
            copy.flip();
            peer.owner.receive(peer, copy);
            if (lowThreshold >= 0) {
                for (RTCTransportListener listener : owner.listeners) {
                    listener.onRTCBufferedAmountLow(this);
                }
            }
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
            this.lowThreshold = threshold;
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<Void> close() {
            this.closed = true;
            for (RTCTransportListener listener : owner.listeners) {
                listener.onRTCChannelClosed(this);
            }
            return AsyncTask.completed(null);
        }
    }
}
