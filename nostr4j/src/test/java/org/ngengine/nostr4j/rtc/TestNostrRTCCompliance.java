package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCDisconnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNCodec;
import org.ngengine.nostr4j.rtc.turn.event.NostrTURNConnectEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

import static org.junit.Assert.assertArrayEquals;
import java.nio.ByteOrder;
import java.util.ArrayList;
public class TestNostrRTCCompliance {

    private static final String APP_ID = "test-app";
    private static final String PROTOCOL_ID = "test-proto";

    private static NostrRTCLocalPeer localPeer(NostrKeyPairSigner signer, NostrKeyPair roomKeyPair, String sessionId, String turn) {
        return new NostrRTCLocalPeer(
            signer,
            Collections.emptyList(),
            APP_ID,
            PROTOCOL_ID,
            sessionId,
            roomKeyPair,
            turn
        );
    }

    private static NostrRTCPeer remotePeer(NostrRTCLocalPeer peer, NostrPublicKey roomPubkey, String turn) {
        return new NostrRTCPeer(
            peer.getPubkey(),
            APP_ID,
            PROTOCOL_ID,
            peer.getSessionId(),
            roomPubkey,
            turn
        );
    }

    @Test
    public void testTurnConnectGeneratesRoomProofAndParses() {
        NostrKeyPair roomKeyPair = new NostrKeyPair();

        NostrKeyPairSigner aliceSigner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner bobSigner = NostrKeyPairSigner.generate();

        NostrRTCLocalPeer aliceLocal = localPeer(aliceSigner, roomKeyPair, "alice-session", "wss://alice.turn");
        NostrRTCLocalPeer bobLocal = localPeer(bobSigner, roomKeyPair, "bob-session", "wss://bob.turn");

        NostrRTCPeer bobRemote = remotePeer(bobLocal, roomKeyPair.getPublicKey(), "wss://bob.turn");
        NostrRTCPeer aliceRemote = remotePeer(aliceLocal, roomKeyPair.getPublicKey(), "wss://alice.turn");

        String challenge = "test-challenge";
        int requiredDifficulty = 10;

        NostrTURNConnectEvent outgoing = NostrTURNConnectEvent.createConnect(
            aliceLocal,
            bobRemote,
            roomKeyPair,
            "default",
            challenge,
            1001L,
            requiredDifficulty
        );

        SignedNostrEvent event = NGEUtils.awaitNoThrow(outgoing.toEvent());
        assertNotNull(event);
        assertNotNull(event.getFirstTag("roomproof"));
        assertTrue(event.getFirstTag("roomproof").size() >= 2);

        NostrTURNConnectEvent incoming = NostrTURNConnectEvent.parseIncoming(
            event,
            bobLocal,
            aliceRemote,
            roomKeyPair,
            "default",
            requiredDifficulty,
            challenge,
            1001L
        );

        assertEquals(challenge, incoming.getChallenge());
    }

    @Test
    public void testTurnChallengeParsesWithoutPeerTags() {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrRTCLocalPeer local = localPeer(signer, roomKeyPair, "server-session", "wss://turn.example");
        UnsignedNostrEvent unsigned = new UnsignedNostrEvent()
            .withKind(25051)
            .createdAt(Instant.now())
            .withTag("t", "challenge")
            .withExpiration(Instant.now().plusSeconds(30))
            .withContent(NGEUtils.getPlatform().toJSON(Map.of("difficulty", Integer.valueOf(12), "challenge", "abc123")));
        SignedNostrEvent event = NGEUtils.awaitNoThrow(signer.sign(unsigned));
        assertNotNull(event);
        assertEquals(12, org.ngengine.nostr4j.rtc.turn.event.NostrTURNChallengeEvent.parseIncoming(event, local, 24).getRequiredDifficulty());
    }

    @Test
    public void testTurnCodecHeaderExtractionMatchesFramePrefix() {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrRTCLocalPeer local = localPeer(signer, roomKeyPair, "session-a", "wss://turn.example");
        NostrRTCPeer remote = new NostrRTCPeer(
            NostrKeyPairSigner.generate().getKeyPair().getPublicKey(),
            APP_ID,
            PROTOCOL_ID,
            "session-b",
            roomKeyPair.getPublicKey(),
            "wss://turn.example"
        );

        SignedNostrEvent connectHeader = NGEUtils.awaitNoThrow(
            NostrTURNConnectEvent.createConnect(local, remote, roomKeyPair, "dc", "hello", 2001L, 6).toEvent()
        );

        ByteBuffer frame = NostrTURNCodec.encodeFrame(
            NostrTURNCodec.encodeHeader(connectHeader),
            2001L,
            List.of("payload".getBytes())
        );

        byte[] extracted = NostrTURNCodec.extractHeaderFrame(frame);
        assertTrue(NostrTURNCodec.compareHeaders(frame, extracted));
    }

    @Test
    public void testRtcOfferAndRouteAreSingleEncryptedAndRoundTrip() {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrKeyPairSigner aliceSigner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner bobSigner = NostrKeyPairSigner.generate();

        NostrRTCLocalPeer alice = localPeer(aliceSigner, roomKeyPair, "alice-session", "wss://alice.turn");
        NostrRTCLocalPeer bob = localPeer(bobSigner, roomKeyPair, "bob-session", "wss://bob.turn");

        String sdp = "v=0\\r\\no=- 1 1 IN IP4 127.0.0.1\\r\\n";
        NostrRTCOfferSignal offerOut = new NostrRTCOfferSignal(aliceSigner, roomKeyPair, alice, sdp);
        SignedNostrEvent offerEvent = NGEUtils.awaitNoThrow(offerOut.toEvent(bob.getPubkey()));

        NostrRTCOfferSignal offerIn = new NostrRTCOfferSignal(bobSigner, roomKeyPair, offerEvent);
        assertEquals(sdp, offerIn.getOfferString());

        RTCTransportIceCandidate candidate = new RTCTransportIceCandidate("candidate:1 1 UDP 1 127.0.0.1 9999 typ host", "0");
        NostrRTCRouteSignal routeOut = new NostrRTCRouteSignal(
            aliceSigner,
            roomKeyPair,
            alice,
            List.of(candidate),
            "wss://alice.turn"
        );
        SignedNostrEvent routeEvent = NGEUtils.awaitNoThrow(routeOut.toEvent(bob.getPubkey()));

        NostrRTCRouteSignal routeIn = new NostrRTCRouteSignal(bobSigner, roomKeyPair, routeEvent);
        assertEquals("wss://alice.turn", routeIn.getTurnServer());
        assertEquals(1, routeIn.getCandidates().size());
        assertEquals(candidate.getCandidate(), routeIn.getCandidates().iterator().next().getCandidate());
    }

    @Test
    public void testRtcSignalParserRejectsTypeMismatch() {
        NostrKeyPair roomKeyPair = new NostrKeyPair();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrRTCLocalPeer local = localPeer(signer, roomKeyPair, "session-x", null);

        NostrRTCConnectSignal connect = new NostrRTCConnectSignal(
            signer,
            roomKeyPair,
            local,
            Instant.now().plusSeconds(60),
            "hello"
        );

        SignedNostrEvent event = NGEUtils.awaitNoThrow(connect.toEvent(null));

        try {
            new NostrRTCDisconnectSignal(signer, roomKeyPair, event);
            fail("Expected type mismatch validation to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Event type"));
        }
    }

 

    private SignedNostrEvent newHeader() {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        UnsignedNostrEvent ev = new UnsignedNostrEvent()
            .withKind(25051)
            .createdAt(Instant.now())
            .withTag("t", "data")
            .withTag("enc", "nip44-v2", "dummy")
            .withContent("");
        return NGEUtils.awaitNoThrow(signer.sign(ev));
    }

    @Test
    public void testCodecEncodeDecodePayloadsRoundTrip() {
        SignedNostrEvent header = newHeader();
        List<byte[]> payloads = List.of("hello".getBytes(), "world".getBytes());

        ByteBuffer frame = NostrTURNCodec.encodeFrame(NostrTURNCodec.encodeHeader(header), 3001L, payloads);

        List<byte[]> decoded = new ArrayList<byte[]>();
        NostrTURNCodec.decodePayloads(frame, decoded);

        assertEquals(2, decoded.size());
        assertArrayEquals("hello".getBytes(), decoded.get(0));
        assertArrayEquals("world".getBytes(), decoded.get(1));
    }

 

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeRejectsWrongVersion() {
        ByteBuffer invalid = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        invalid.put((byte) 99); // bad version
        invalid.putShort((short) 1);
        invalid.put((byte) '{');
        invalid.putShort((short) 0);
        invalid.flip();

        NostrTURNCodec.decodeHeader(invalid);
    }

}
