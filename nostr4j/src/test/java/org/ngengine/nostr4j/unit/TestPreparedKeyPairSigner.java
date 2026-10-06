/** BSD 3-Clause License. Copyright (c) 2026, Riccardo Balbo. */
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;

public class TestPreparedKeyPairSigner {

    @Test
    public void serializesPreparedSignerAndRebuildsRuntimeContext() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair(NostrPrivateKey.fromHex("00".repeat(31) + "03")));
        assertTrue(signer.sign(new UnsignedNostrEvent().withContent("before serialization")).await().verify());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(signer);
        }
        signer.getKeyPair().destroy();
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            NostrKeyPairSigner restored = (NostrKeyPairSigner) input.readObject();
            assertTrue(restored.sign(new UnsignedNostrEvent().withContent("after serialization")).await().verify());
            restored.getKeyPair().destroy();
        }
    }

    @Test
    public void readsSignerWrittenByPreviousSnapshot() throws Exception {
        assertEquals(7419600314608918328L, ObjectStreamClass.lookup(NostrKeyPairSigner.class).getSerialVersionUID());
        try (ObjectInputStream input = new ObjectInputStream(getClass().getResourceAsStream("/round2-keypair-signer.ser"))) {
            NostrKeyPairSigner restored = (NostrKeyPairSigner) input.readObject();
            assertTrue(restored.sign(new UnsignedNostrEvent().withContent("legacy signer")).await().verify());
            restored.getKeyPair().destroy();
        }
    }

    @Test
    public void concurrentSignerReuseAndCloneProduceVerifiableEvents() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair(NostrPrivateKey.fromHex("00".repeat(31) + "03")));
        List<AsyncTask<SignedNostrEvent>> tasks = new ArrayList<>();
        for (int i = 0; i < 32; i++) tasks.add(signer.sign(new UnsignedNostrEvent().withContent("message " + i)));
        for (AsyncTask<SignedNostrEvent> task : tasks) {
            SignedNostrEvent event = task.await();
            assertTrue(event.verify());
            assertEquals(signer.getPublicKey().await(), event.getPubkey());
        }
        NostrKeyPairSigner clone = signer.clone();
        signer.getKeyPair().destroy();
        assertTrue(clone.sign(new UnsignedNostrEvent().withContent("independent clone")).await().verify());
        assertThrows(IllegalStateException.class, () -> signer.sign(new UnsignedNostrEvent()));
        clone.getKeyPair().destroy();
    }
}
