/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.signal;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestNostrRTCLinkAdmissionSignal {

    @Test
    public void capabilityIsOptionalOnDc4AndDc3KeepsLegacyBehavior() throws Exception {
        try (NostrKeyPair room = new NostrKeyPair()) {
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            NostrRTCLocalPeer local = local(room, signer, "source");
            NostrRTCConnectSignal signal = new NostrRTCConnectSignal(signer, room, local, Instant.now().plusSeconds(60), "");
            SignedNostrEvent event = signal.toEvent(null).await();
            assertTrue(new NostrRTCConnectSignal(signer, room, event).getPeer().supportsLinkAdmission());
            for (String version : new String[] { "dc3", "dc4" }) {
                UnsignedNostrEvent legacy = new UnsignedNostrEvent()
                    .withKind(25050)
                    .withTag("t", "connect")
                    .withTag("P", room.getPublicKey().asHex())
                    .withTag("d", "legacy")
                    .withTag("i", "proto")
                    .withTag("y", "app")
                    .withTag("version", version)
                    .withTag("expiration", String.valueOf(Instant.now().plusSeconds(60).getEpochSecond()));
                // A production legacy presence also has a room proof.
                NostrRTCConnectSignal outgoing = new NostrRTCConnectSignal(
                    signer,
                    room,
                    local,
                    Instant.now().plusSeconds(60),
                    ""
                );
                SignedNostrEvent signed = outgoing.signForRoom(legacy).await();
                assertFalse(new NostrRTCConnectSignal(signer, room, signed).getPeer().supportsLinkAdmission());
            }
        }
    }

    @Test
    public void encryptedCommandsAndDescriptionsRetainSessionAttemptBinding() throws Exception {
        try (NostrKeyPair room = new NostrKeyPair()) {
            NostrKeyPairSigner sender = NostrKeyPairSigner.generate(), receiver = NostrKeyPairSigner.generate();
            NostrRTCLocalPeer local = local(room, sender, "sender-session");
            String attempt = "a".repeat(32);
            for (NostrRTCLinkSignal.Command command : NostrRTCLinkSignal.Command.values()) {
                SignedNostrEvent event = new NostrRTCLinkSignal(sender, room, local, command, attempt, "receiver-session")
                    .toEvent(receiver.getPublicKey().await())
                    .await();
                assertNotEquals(command.name(), event.getContent());
                NostrRTCLinkSignal parsed = new NostrRTCLinkSignal(receiver, room, event);
                assertEquals(command, parsed.getCommand());
                assertEquals(attempt, parsed.getLinkAttemptId());
                assertEquals("receiver-session", parsed.getTargetSession());
            }
            NostrRTCOfferSignal offer = new NostrRTCOfferSignal(sender, room, local, "test-sdp");
            offer.withLinkAttempt(attempt, "receiver-session");
            NostrRTCOfferSignal parsed = new NostrRTCOfferSignal(
                receiver,
                room,
                offer.toEvent(receiver.getPublicKey().await()).await()
            );
            assertEquals("test-sdp", parsed.getOfferString());
            assertEquals(attempt, parsed.getLinkAttemptId());
        }
    }

    @Test
    public void roomProofRejectsRetargetedAttemptsEvenWithAValidSenderSignature() throws Exception {
        try (NostrKeyPair room = new NostrKeyPair()) {
            NostrKeyPairSigner sender = NostrKeyPairSigner.generate(), receiver = NostrKeyPairSigner.generate();
            SignedNostrEvent original = new NostrRTCLinkSignal(
                sender,
                room,
                local(room, sender, "source"),
                NostrRTCLinkSignal.Command.REQUEST,
                "a".repeat(32),
                "target"
            )
                .toEvent(receiver.getPublicKey().await())
                .await();
            for (String changed : new String[] { "link-attempt", "target-session", "d", "p", "expiration" }) {
                UnsignedNostrEvent mutated = new UnsignedNostrEvent().withKind(original.getKind());
                mutated.createdAt(original.getCreatedAt());
                mutated.withContent(original.getContent());
                for (List<String> row : original.getTagRows()) {
                    if (row.get(0).equals(changed)) {
                        String value = changed.equals("link-attempt") || changed.equals("p")
                            ? "b".repeat(changed.equals("p") ? 64 : 32)
                            : changed.equals("expiration")
                                ? String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond())
                                : "changed-session";
                        mutated.withTag(changed, value);
                    } else mutated.withTag(row.get(0), row.subList(1, row.size()));
                }
                SignedNostrEvent signed = sender.sign(mutated).await();
                assertTrue(signed.verify());
                try {
                    new NostrRTCLinkSignal(receiver, room, signed);
                    fail("Retargeting must invalidate the room proof: " + changed);
                } catch (IllegalArgumentException expected) {}
            }
        }
    }

    @Test
    public void malformedAttemptBindingIsRejected() {
        try (NostrKeyPair room = new NostrKeyPair()) {
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            try {
                new NostrRTCLinkSignal(
                    signer,
                    room,
                    local(room, signer, "s"),
                    NostrRTCLinkSignal.Command.REQUEST,
                    "short",
                    "target"
                );
                fail();
            } catch (IllegalArgumentException expected) {}
        }
    }

    private NostrRTCLocalPeer local(NostrKeyPair room, NostrKeyPairSigner signer, String session) {
        return new NostrRTCLocalPeer(RTCSettings.getDefault("app", "proto"), signer, session, room, null);
    }
}
