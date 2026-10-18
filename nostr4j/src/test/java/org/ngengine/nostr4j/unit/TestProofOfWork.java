/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.utils.NostrRoomProof;

public class TestProofOfWork {

    @Test
    public void miningPreservesExplicitCreationTime() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        Instant createdAt = Instant.now().minusSeconds(60);
        UnsignedNostrEvent event = new UnsignedNostrEvent().createdAt(createdAt).withKind(1).withContent("test");

        var signed = signer.powSign(event, 4).await();

        assertEquals(createdAt, signed.getCreatedAt());
        assertTrue(signed.verify());
        assertTrue(signed.checkPow(4));
    }

    @Test
    public void miningPreservesRoomMembershipProof() throws Exception {
        NostrKeyPair room = new NostrKeyPair();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        var sender = signer.getPublicKey().await();
        Instant createdAt = Instant.now().minusSeconds(60);
        String challenge = "turn-connection-challenge";
        UnsignedNostrEvent event = new UnsignedNostrEvent().createdAt(createdAt).withKind(25051);
        String proofId = NostrRoomProof.computeId(room.getPublicKey(), createdAt, event.getKind(), sender, challenge);
        String proofSignature = NostrRoomProof.sign(room, createdAt, event.getKind(), sender, challenge).await();
        event.withTag("roomproof", proofId, proofSignature);

        var signed = signer.powSign(event, 4).await();

        assertTrue(signed.verify());
        assertTrue(signed.checkPow(4));
        assertTrue(
            "mining must not invalidate the previously signed room proof",
            NostrRoomProof.verify(
                room.getPublicKey(),
                signed.getCreatedAt(),
                signed.getKind(),
                signed.getPubkey(),
                challenge,
                signed.getFirstTagFirstValue("roomproof"),
                signed.getFirstTagSecondValue("roomproof")
            )
        );
    }
}
