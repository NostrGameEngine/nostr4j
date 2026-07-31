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

package org.ngengine.nostr4j.rtc.turn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCProtocolVersion;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestNostrTURNDataRouting {

    private static final String APPLICATION = "turn-routing-test";
    private static final String PROTOCOL = "routing-v1";

    @Test
    public void testDc4RoundTripMultiplePayloadsAndInputImmutability() {
        Fixture fixture = fixture(NostrRTCProtocolVersion.CURRENT_NIP_DC_VERSION);
        ByteBuffer key = key();
        ByteBuffer first = positioned("xxfirst");
        ByteBuffer second = positioned("yysecond");
        int firstPosition = first.position();
        int firstLimit = first.limit();
        int secondPosition = second.position();
        int secondLimit = second.limit();

        NostrTURNDataEvent outgoing = fixture.outgoing("default", key);
        ByteBuffer frame = NGEUtils.awaitNoThrow(outgoing.encodeToFrame(List.of(first, second), 11));

        assertEquals(firstPosition, first.position());
        assertEquals(firstLimit, first.limit());
        assertEquals(secondPosition, second.position());
        assertEquals(secondLimit, second.limit());

        Collection<ByteBuffer> decoded = decode(fixture, frame, "default");
        List<ByteBuffer> payloads = new ArrayList<ByteBuffer>(decoded);
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), bytes(payloads.get(0)));
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), bytes(payloads.get(1)));
        assertTrue(payloads.get(0).isReadOnly());
        assertTrue(payloads.get(1).isReadOnly());
    }

    @Test
    public void testDc4ReusableHeaderAndMaliciousRouteRewriteRejected() throws Exception {
        Fixture fixture = fixture(NostrRTCProtocolVersion.CURRENT_NIP_DC_VERSION);
        NostrTURNDataEvent outgoing = fixture.outgoing("default", key());
        ByteBuffer first = outgoing.encodeToFrame(List.of(buffer("first")), 21).await();
        ByteBuffer second = outgoing.encodeToFrame(List.of(buffer("second")), 22).await();

        assertArrayEquals(
            NostrTURNCodec.encodeHeader(NostrTURNCodec.decodeHeader(first)),
            NostrTURNCodec.encodeHeader(NostrTURNCodec.decodeHeader(second))
        );
        ByteBuffer maliciouslyRewritten = NostrTURNCodec.withVsocketId(first, 9902L);
        expectDecodeFailure(fixture, maliciouslyRewritten, "rewritten-channel");
    }

    @Test
    public void testDc4RejectsCompleteMixedRoutingFrame() throws Exception {
        Fixture fixture = fixture(NostrRTCProtocolVersion.CURRENT_NIP_DC_VERSION);
        ByteBuffer sharedKey = key();
        ByteBuffer validFrame = fixture.outgoing("default", sharedKey).encodeToFrame(List.of(buffer("valid")), 31).await();
        ByteBuffer wrongFrame = fixture.outgoing("other", sharedKey).encodeToFrame(List.of(buffer("wrong")), 32).await();
        List<ByteBuffer> validPayloads = encryptedPayloads(validFrame);
        List<ByteBuffer> wrongPayloads = encryptedPayloads(wrongFrame);
        ByteBuffer mixed = NostrTURNCodec.encodeFrameBuffers(
            NostrTURNCodec.encodeHeader(NostrTURNCodec.decodeHeader(validFrame)),
            NostrTURNCodec.extractVsocketId(validFrame),
            33,
            List.of(validPayloads.get(0), wrongPayloads.get(0))
        );

        expectDecodeFailure(fixture, mixed, "default");
    }

    @Test
    public void testDc4RejectsMissingRoutingHash() throws Exception {
        Fixture fixture = fixture(NostrRTCProtocolVersion.CURRENT_NIP_DC_VERSION);
        ByteBuffer sharedKey = key();
        ByteBuffer validFrame = fixture.outgoing("default", sharedKey).encodeToFrame(List.of(buffer("valid")), 34).await();
        ByteBuffer shortPlaintext = Nip44.encryptBinary(buffer("too-short"), sharedKey).await();
        ByteBuffer malformed = NostrTURNCodec.encodeFrameBuffers(
            NostrTURNCodec.encodeHeader(NostrTURNCodec.decodeHeader(validFrame)),
            NostrTURNCodec.extractVsocketId(validFrame),
            35,
            List.of(shortPlaintext)
        );

        expectDecodeFailure(fixture, malformed, "default");
    }

    @Test
    public void testDc3PayloadRemainsUnprefixedAndAccepted() throws Exception {
        Fixture fixture = fixture(NostrRTCProtocolVersion.MIN_SUPPORTED_NIP_DC_VERSION);
        ByteBuffer sharedKey = key();
        byte[] original = "legacy-dc3".getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = fixture.outgoing("default", sharedKey).encodeToFrame(List.of(ByteBuffer.wrap(original)), 41).await();

        List<ByteBuffer> encrypted = encryptedPayloads(frame);
        ByteBuffer rawPlaintext = Nip44.decryptBinary(encrypted.get(0), sharedKey).await();
        assertArrayEquals(original, bytes(rawPlaintext));

        Collection<ByteBuffer> decoded = decode(fixture, frame, "default");
        assertArrayEquals(original, bytes(decoded.iterator().next()));
    }

    @Test
    public void testDc4PlaintextBoundary() throws Exception {
        Fixture fixture = fixture(NostrRTCProtocolVersion.CURRENT_NIP_DC_VERSION);
        NostrTURNDataEvent outgoing = fixture.outgoing("default", key());
        ByteBuffer maximum = ByteBuffer.allocate(NostrTURNDataEvent.MAX_FRAMED_PAYLOAD_SIZE);
        outgoing.encodeToFrame(List.of(maximum), 51).await();

        try {
            outgoing.encodeToFrame(List.of(ByteBuffer.allocate(NostrTURNDataEvent.MAX_FRAMED_PAYLOAD_SIZE + 1)), 52);
            fail("Expected oversized dc4 payload rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maximum supported"));
        }
    }

    private static Collection<ByteBuffer> decode(Fixture fixture, ByteBuffer frame, String channel) {
        SignedNostrEvent header = NostrTURNCodec.decodeHeader(frame);
        NostrTURNDataEvent incoming = NostrTURNDataEvent.parseIncoming(
            header,
            fixture.bob,
            fixture.aliceRemote,
            fixture.room,
            channel,
            NostrTURNCodec.extractVsocketId(frame)
        );
        return NGEUtils.awaitNoThrow(incoming.decodeFramePayloads(frame));
    }

    private static void expectDecodeFailure(Fixture fixture, ByteBuffer frame, String channel) throws Exception {
        SignedNostrEvent header = NostrTURNCodec.decodeHeader(frame);
        NostrTURNDataEvent incoming = NostrTURNDataEvent.parseIncoming(
            header,
            fixture.bob,
            fixture.aliceRemote,
            fixture.room,
            channel,
            NostrTURNCodec.extractVsocketId(frame)
        );
        try {
            incoming.decodeFramePayloads(frame).await();
            fail("Expected routing hash validation failure");
        } catch (Exception expected) {
            assertTrue(hasMessage(expected, "routing hash"));
        }
    }

    private static boolean hasMessage(Throwable error, String expected) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static List<ByteBuffer> encryptedPayloads(ByteBuffer frame) {
        List<ByteBuffer> payloads = new ArrayList<ByteBuffer>();
        NostrTURNCodec.decodePayloadBuffers(frame, payloads);
        return payloads;
    }

    private static ByteBuffer positioned(String value) {
        ByteBuffer buffer = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
        buffer.position(2);
        return buffer;
    }

    private static ByteBuffer buffer(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer view = buffer.duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }

    private static ByteBuffer key() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    private static Fixture fixture(int version) {
        NostrKeyPair room = new NostrKeyPair();
        NostrRTCLocalPeer alice = localPeer(NostrKeyPairSigner.generate(), room, "alice", version);
        NostrRTCLocalPeer bob = localPeer(NostrKeyPairSigner.generate(), room, "bob", version);
        NostrRTCPeer bobRemote = remotePeer(bob, room, version);
        NostrRTCPeer aliceRemote = remotePeer(alice, room, version);
        return new Fixture(room, alice, bob, bobRemote, aliceRemote);
    }

    private static NostrRTCLocalPeer localPeer(NostrKeyPairSigner signer, NostrKeyPair room, String session, int version) {
        NostrRTCLocalPeer peer = new NostrRTCLocalPeer(
            signer,
            Collections.emptyList(),
            APPLICATION,
            PROTOCOL,
            session,
            room,
            null
        );
        peer.setNipDcVersion(version);
        return peer;
    }

    private static NostrRTCPeer remotePeer(NostrRTCLocalPeer source, NostrKeyPair room, int version) {
        return new NostrRTCPeer(
            source.getPubkey(),
            APPLICATION,
            PROTOCOL,
            source.getSessionId(),
            room.getPublicKey(),
            null,
            version
        );
    }

    private static final class Fixture {

        private final NostrKeyPair room;
        private final NostrRTCLocalPeer alice;
        private final NostrRTCLocalPeer bob;
        private final NostrRTCPeer bobRemote;
        private final NostrRTCPeer aliceRemote;

        private Fixture(
            NostrKeyPair room,
            NostrRTCLocalPeer alice,
            NostrRTCLocalPeer bob,
            NostrRTCPeer bobRemote,
            NostrRTCPeer aliceRemote
        ) {
            this.room = room;
            this.alice = alice;
            this.bob = bob;
            this.bobRemote = bobRemote;
            this.aliceRemote = aliceRemote;
        }

        private NostrTURNDataEvent outgoing(String channel, ByteBuffer key) {
            return NostrTURNDataEvent.createOutgoing(alice, bobRemote, room, channel, 9001L, key);
        }
    }
}
