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

package org.ngengine.nostr4j.rtc.signal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.Collections;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestNostrRTCProtocolVersion {

    @Test
    public void testCurrentVersionAdvertisedAndParsed() {
        NostrKeyPair room = new NostrKeyPair();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrRTCLocalPeer local = localPeer(signer, room, "local");
        NostrRTCConnectSignal outgoing = new NostrRTCConnectSignal(signer, room, local, Instant.now().plusSeconds(60), "");

        SignedNostrEvent event = NGEUtils.awaitNoThrow(outgoing.toEvent(null));
        assertEquals("dc4", event.getFirstTagFirstValue("version"));

        NostrRTCConnectSignal incoming = new NostrRTCConnectSignal(signer, room, event);
        assertEquals(NostrRTCProtocolVersion.CURRENT_NIP_DC_VERSION, incoming.getPeer().getNipDcVersion());
    }

    @Test
    public void testDc3ReceivedAndRetainedByPeerMerge() {
        NostrKeyPair room = new NostrKeyPair();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        SignedNostrEvent event = connectEvent(signer, room, "dc3");
        NostrRTCConnectSignal incoming = new NostrRTCConnectSignal(signer, room, event);

        NostrRTCPeer cached = new NostrRTCPeer(
            incoming.getPeer().getPubkey(),
            incoming.getPeer().getApplicationId(),
            incoming.getPeer().getProtocolId(),
            incoming.getPeer().getSessionId(),
            incoming.getPeer().getRoomPubkey(),
            null
        );
        int originalHashCode = cached.hashCode();
        cached.mergeAuthenticatedAnnouncement(incoming.getPeer());

        assertEquals(NostrRTCProtocolVersion.MIN_SUPPORTED_NIP_DC_VERSION, cached.getNipDcVersion());
        assertEquals(originalHashCode, cached.hashCode());
        assertEquals(cached, incoming.getPeer());

        NostrRTCPeer nonPresenceSignalPeer = new NostrRTCPeer(
            cached.getPubkey(),
            cached.getApplicationId(),
            cached.getProtocolId(),
            cached.getSessionId(),
            cached.getRoomPubkey(),
            null
        );
        cached.merge(nonPresenceSignalPeer);
        assertEquals(NostrRTCProtocolVersion.MIN_SUPPORTED_NIP_DC_VERSION, cached.getNipDcVersion());
    }

    @Test
    public void testStrictParserRejectsMalformedAndUnsupportedVersions() {
        String[] invalid = { null, "", "dc", "DC4", "dc03", "dc2", "dc5", "dc-4", "dc4 ", " dc4", "dc2147483648" };
        for (String value : invalid) {
            try {
                NostrRTCProtocolVersion.parse(value);
                fail("Expected version rejection for: " + value);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
        assertEquals(3, NostrRTCProtocolVersion.parse("dc3"));
        assertEquals(4, NostrRTCProtocolVersion.parse("dc4"));
    }

    private static SignedNostrEvent connectEvent(NostrKeyPairSigner signer, NostrKeyPair room, String version) {
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withKind(25050)
            .createdAt(Instant.now())
            .withTag("t", "connect")
            .withTag("P", room.getPublicKey().asHex())
            .withTag("d", "remote")
            .withTag("i", "protocol")
            .withTag("version", version)
            .withTag("y", "application")
            .withTag("expiration", String.valueOf(Instant.now().plusSeconds(60).getEpochSecond()))
            .withContent("");
        return NGEUtils.awaitNoThrow(signer.sign(event));
    }

    private static NostrRTCLocalPeer localPeer(NostrKeyPairSigner signer, NostrKeyPair room, String session) {
        return new NostrRTCLocalPeer(signer, Collections.emptyList(), "application", "protocol", session, room, null);
    }
}
