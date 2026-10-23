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

package org.ngengine.nostr4j.nip53;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestNip53 {

    private static final String PUBLIC_KEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private SignedNostrEvent event(int kind, Instant created, List<List<String>> tags) {
        return new SignedNostrEvent(
            "a".repeat(64),
            NostrPublicKey.fromHex(PUBLIC_KEY),
            kind,
            "",
            created,
            "c".repeat(128),
            tags
        );
    }

    @Test
    public void parsesStreamsAndRoomsWithoutConfusingTheirTransports() {
        SignedNostrEvent event = event(
            30311,
            Instant.now(),
            List.of(
                List.of("d", "room:one"),
                List.of("title", "Live title"),
                List.of("status", "live"),
                List.of("streaming", "wss+livekit://room.test"),
                List.of("streaming", "https://room.test/live.m3u8"),
                List.of("current_participants", "12"),
                List.of("relays", "wss://relay.test", "javascript:bad")
            )
        );
        Nip53.Activity parsed = Nip53.activity(event);
        assertEquals("30311:" + PUBLIC_KEY + ":room:one", parsed.coordinate);
        assertEquals("wss://room.test", parsed.interactiveServer);
        assertEquals("https://room.test/live.m3u8", parsed.playback());
        assertEquals(12, parsed.participants);
        assertEquals(List.of("wss://relay.test"), parsed.relays);
        assertTrue(parsed.interactive());
        Nip53.Activity space = Nip53.activity(
            event(
                30312,
                Instant.now(),
                List.of(
                    List.of("d", "room"),
                    List.of("room", "Audio room"),
                    List.of("status", "open"),
                    List.of("service", "https://room.test/one")
                )
            )
        );
        assertEquals("Audio room", space.title);
        assertEquals("https://room.test/one", space.service);
    }

    @Test
    public void staleLiveUsesRecordingAndUnsafeUrlsCannotBecomeMedia() {
        Nip53.Activity old = Nip53.activity(
            event(
                30311,
                Instant.now().minusSeconds(3700),
                List.of(
                    List.of("d", "old"),
                    List.of("status", "live"),
                    List.of("recording", "https://room.test/recording.mp4"),
                    List.of("image", "javascript:alert(1)")
                )
            )
        );
        assertEquals("ended", old.status);
        assertEquals("https://room.test/recording.mp4", old.playback());
        assertEquals("", old.image);
        for (String source : List.of(
            "javascript:alert(1)",
            "https://user:secret@room.test/x",
            "https://room.test\\@evil.test/x",
            "http://remote.test/x",
            "https://room.test/x\nfoo"
        )) assertEquals("", Nip53.web(source));
        assertThrows(IllegalArgumentException.class, () -> Nip53.activity(event(1, Instant.now(), List.of())));
    }

    @Test
    public void chatAndPresenceHaveSeparateKindsAndCorrectTargets() {
        String coordinate = "30311:" + "b".repeat(64) + ":room";
        UnsignedNostrEvent chat = Nip53.chat(coordinate, "wss://relay.test", "hello");
        assertEquals(1311, chat.getKind());
        assertEquals(coordinate, chat.getFirstTagFirstValue("a"));
        UnsignedNostrEvent presence = Nip53.presence(coordinate, "wss://relay.test", true);
        assertEquals(10312, presence.getKind());
        assertEquals("1", presence.getFirstTagFirstValue("hand"));
        assertNotNull(presence.getFirstTag("expiration"));
        assertThrows(IllegalArgumentException.class, () -> Nip53.chat("invalid", "", "hello"));
        assertThrows(IllegalArgumentException.class, () -> Nip53.chat(coordinate, "", " "));
    }

    @Test
    public void participationProofMustBeSignedByTheParticipantForThisActivity() throws Exception {
        NostrKeyPairSigner participant = NostrKeyPairSigner.generate();
        try {
            String publicKey = participant.getPublicKey().await().asHex();
            String coordinate = "30311:" + PUBLIC_KEY + ":activity";
            String proof = NGEUtils
                .getPlatform()
                .createSchnorrSigner(participant.getKeyPair().getPrivateKey()::asReadOnlyBuffer)
                .sign(NGEUtils.getPlatform().sha256(coordinate))
                .await();
            SignedNostrEvent activity = event(
                30311,
                Instant.now(),
                List.of(List.of("d", "activity"), List.of("p", publicKey, "", "Speaker", proof))
            );
            assertTrue(Nip53.participationProof(activity, publicKey).await());
            SignedNostrEvent different = event(
                30311,
                Instant.now(),
                List.of(List.of("d", "different"), List.of("p", publicKey, "", "Speaker", proof))
            );
            assertFalse(Nip53.participationProof(different, publicKey).await());
        } finally {
            participant.getKeyPair().destroy();
        }
    }

    @Test
    public void invitationsAndMalformedProofsDoNotEstablishParticipation() throws Exception {
        SignedNostrEvent invitation = event(
            30311,
            Instant.now(),
            List.of(List.of("d", "activity"), List.of("p", PUBLIC_KEY, "", "Speaker"))
        );
        assertFalse(Nip53.participationProof(invitation, PUBLIC_KEY).await());
        SignedNostrEvent malformed = event(
            30311,
            Instant.now(),
            List.of(List.of("d", "activity"), List.of("p", PUBLIC_KEY, "", "Speaker", "invalid"))
        );
        assertFalse(Nip53.participationProof(malformed, PUBLIC_KEY).await());
        assertFalse(Nip53.participationProof(malformed, "invalid").await());
    }

    @Test
    public void numberConversionKeepsZeroFallbackAndNonNegativeValues() {
        for (String value : List.of("", "-1", "invalid", "9223372036854775808")) {
            Nip53.Activity parsed = Nip53.activity(
                event(
                    30311,
                    Instant.now(),
                    List.of(List.of("d", "activity"), List.of("starts", value), List.of("current_participants", value))
                )
            );
            assertEquals(0, parsed.starts);
            assertEquals(0, parsed.participants);
        }
        Nip53.Activity valid = Nip53.activity(
            event(
                30311,
                Instant.now(),
                List.of(
                    List.of("d", "activity"),
                    List.of("starts", "+123"),
                    List.of("current_participants", Long.toString(Long.MAX_VALUE))
                )
            )
        );
        assertEquals(123, valid.starts);
        assertEquals(Long.MAX_VALUE, valid.participants);
        assertEquals("", valid.title);
    }
}
