package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrAnonSigner;

public class TestAnonSigner {

    @Test
    public void eachSignUsesANewKeyWithoutPublishingMetadata() throws Exception {
        NostrAnonSigner signer = new NostrAnonSigner();
        UnsignedNostrEvent request = new UnsignedNostrEvent().withKind(1).withContent("Ephemeral local fixture");
        SignedNostrEvent first = signer.sign(request).await();
        SignedNostrEvent second = signer.sign(request).await();

        assertTrue(first.verify());
        assertTrue(second.verify());
        assertNotEquals(first.getPubkey(), second.getPubkey());
        assertEquals(1, first.getKind());
        assertEquals(request.getContent(), first.getContent());
        assertEquals(request.getTagRows(), first.getTagRows());
        assertTrue(signer.isAvailable().await());
    }

    @Test
    public void operationsRequiringAPersistentIdentityAreUnsupported() throws Exception {
        NostrAnonSigner signer = new NostrAnonSigner();
        try (NostrKeyPair peer = new NostrKeyPair()) {
            assertThrows(UnsupportedOperationException.class, () -> signer.getPublicKey().await());
            assertThrows(UnsupportedOperationException.class, () -> signer.encrypt("message", peer.getPublicKey()).await());
            assertThrows(UnsupportedOperationException.class, () -> signer.decrypt("message", peer.getPublicKey()).await());
        }
    }

    @Test
    public void proofOfWorkUsesANewKeyForEachSignedEvent() throws Exception {
        NostrAnonSigner signer = new NostrAnonSigner();
        UnsignedNostrEvent request = new UnsignedNostrEvent().withKind(1).withContent("pow fixture");
        SignedNostrEvent first = signer.powSign(request, 4).await();
        SignedNostrEvent second = signer.powSign(request, 4).await();

        assertTrue(first.verify());
        assertTrue(second.verify());
        assertTrue(first.getId().startsWith("0"));
        assertTrue(second.getId().startsWith("0"));
        assertNotEquals(first.getPubkey(), second.getPubkey());
    }
}
