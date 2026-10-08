/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.signal;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.utils.NostrRoomProof;
import org.ngengine.platform.NGEPlatform;

public class TestNostrRTCWireCompatibility {

    @Test
    public void presenceKeepsExistingDc3AndDc4Tags() throws Exception {
        try (NostrKeyPair room = new NostrKeyPair()) {
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            NostrRTCLocalPeer local = local(room, signer);
            SignedNostrEvent presence = new NostrRTCConnectSignal(signer, room, local, Instant.now().plusSeconds(60), "")
                .toEvent(null)
                .await();
            assertEquals("dc4", presence.getFirstTagFirstValue("version"));
            assertNull(presence.getFirstTagFirstValue("link-admission"));
            for (String version : List.of("dc3", "dc4")) {
                UnsignedNostrEvent event = new UnsignedNostrEvent()
                    .withKind(25050)
                    .withTag("t", "connect")
                    .withTag("P", room.getPublicKey().asHex())
                    .withTag("d", "legacy")
                    .withTag("i", "proto")
                    .withTag("y", "app")
                    .withTag("version", version)
                    .withTag("expiration", String.valueOf(Instant.now().plusSeconds(60).getEpochSecond()));
                NostrRTCConnectSignal outgoing = new NostrRTCConnectSignal(
                    signer,
                    room,
                    local,
                    Instant.now().plusSeconds(60),
                    ""
                );
                NostrRTCConnectSignal parsed = new NostrRTCConnectSignal(signer, room, outgoing.signForRoom(event).await());
                assertEquals(version, parsed.getProtocolVersion());
            }
        }
    }

    @Test
    public void existingRoomProofVerifierAcceptsEncryptedOfferAnswerAndRoute() throws Exception {
        try (NostrKeyPair room = new NostrKeyPair()) {
            NostrKeyPairSigner sender = NostrKeyPairSigner.generate(), receiver = NostrKeyPairSigner.generate();
            NostrRTCLocalPeer local = local(room, sender);
            List<NostrRTCSignal> signals = List.of(
                new NostrRTCOfferSignal(sender, room, local, "offer-sdp"),
                new NostrRTCAnswerSignal(sender, room, local, "answer-sdp"),
                new NostrRTCRouteSignal(sender, room, local, List.of(), "ws://turn.example/turn")
            );
            for (NostrRTCSignal signal : signals) {
                SignedNostrEvent event = signal.toEvent(receiver.getPublicKey().await()).await();
                assertTrue(Set.of("offer", "answer", "route").contains(event.getFirstTagFirstValue("t")));
                assertNull(event.getFirstTagFirstValue("link-attempt"));
                assertNull(event.getFirstTagFirstValue("target-session"));
                var proof = event.getFirstTag("roomproof");
                String legacyChallenge = NGEPlatform
                    .get()
                    .toJSON(List.of(event.getFirstTagFirstValue("p"), event.getContent()));
                assertTrue(
                    NostrRoomProof.verify(
                        room.getPublicKey(),
                        event.getCreatedAt(),
                        event.getKind(),
                        event.getPubkey(),
                        legacyChallenge,
                        proof.get(0),
                        proof.get(1)
                    )
                );
                switch (event.getFirstTagFirstValue("t")) {
                    case "offer":
                        assertEquals("offer-sdp", new NostrRTCOfferSignal(receiver, room, event).getOfferString());
                        break;
                    case "answer":
                        assertEquals("answer-sdp", new NostrRTCAnswerSignal(receiver, room, event).getSdp());
                        break;
                    case "route":
                        assertEquals("ws://turn.example/turn", new NostrRTCRouteSignal(receiver, room, event).getTurnServer());
                        break;
                }
            }
        }
    }

    private static NostrRTCLocalPeer local(NostrKeyPair room, NostrKeyPairSigner signer) {
        return new NostrRTCLocalPeer(RTCSettings.getDefault("app", "proto"), signer, "source", room, null);
    }
}
