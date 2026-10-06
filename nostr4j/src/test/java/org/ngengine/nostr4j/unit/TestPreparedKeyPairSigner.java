/** BSD 3-Clause License. Copyright (c) 2026, Riccardo Balbo. */
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

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
