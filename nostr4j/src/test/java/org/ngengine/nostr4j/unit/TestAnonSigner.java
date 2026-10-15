package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrAnonSigner;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestAnonSigner {

    @Test
    public void actionKeepsOneKeyForCompoundProtocolsThenDisposesIt() throws Exception {
        NostrAnonSigner factory = new NostrAnonSigner();
        NostrAnonSigner.Action action = factory.newAction();
        NostrKeyPairSigner other = NostrKeyPairSigner.generate();
        try {
            NostrPublicKey key = action.getPublicKey().await();
            SignedNostrEvent signed = action.sign(new UnsignedNostrEvent().withKind(9734)).await();
            assertEquals(key, signed.getPubkey());
            String encrypted = action.encrypt("Only one action", other.getPublicKey().await()).await();
            assertEquals("Only one action", other.decrypt(encrypted, key).await());
            String reply = other.encrypt("Reply", key).await();
            assertEquals("Reply", action.decrypt(reply, other.getPublicKey().await()).await());
            try (
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream output = new ObjectOutputStream(bytes)
            ) {
                try {
                    output.writeObject(action);
                    fail("Ephemeral action cannot be serialized");
                } catch (NotSerializableException expected) {}
            }
            action.close().await();
            action.close().await();
            try {
                action.sign(new UnsignedNostrEvent());
                fail("Closed action cannot sign");
            } catch (IllegalStateException expected) {}
            try (
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream output = new ObjectOutputStream(bytes)
            ) {
                output.writeObject(factory);
                assertTrue(bytes.size() < 256);
            }
        } finally {
            action.close();
            other.getKeyPair().destroy();
        }
    }

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
