/** BSD 3-Clause License. Copyright (c) 2026, Riccardo Balbo. */
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestUnicodeCanonicalEvents {

    @Test
    @SuppressWarnings("unchecked")
    public void canonicalIdsMatchIndependentJsonStringifyVectors() throws Exception {
        String fixture;
        try (java.io.InputStream input = getClass().getResourceAsStream("/unicode-canonical-event-vectors.json")) {
            fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Map<String, Object>> vectors = NGEUtils.getPlatform().fromJSON(fixture, List.class);
        assertEquals(27, vectors.size());
        for (Map<String, Object> vector : vectors) {
            SignedNostrEvent event = new SignedNostrEvent(vector);
            String pubkey = (String) vector.get("pubkey");
            assertEquals((String) vector.get("name"), vector.get("id"), NostrEvent.computeEventId(pubkey, event));
            UnsignedNostrEvent unsigned = new UnsignedNostrEvent(event.toMap());
            assertEquals((String) vector.get("name"), vector.get("id"), NostrEvent.computeEventId(pubkey, unsigned));
        }
    }

    @Test
    public void replacingSignedQuestionMarkWithIsolatedSurrogateInvalidatesSignature() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair(NostrPrivateKey.fromHex("00".repeat(31) + "03")));
        try {
            SignedNostrEvent original = signer
                .sign(new UnsignedNostrEvent().withContent("?").createdAt(Instant.ofEpochSecond(1700000000)))
                .await();
            assertTrue(original.verify());
            for (String replacement : List.of("\ud800", "\udc00")) {
                Map<String, Object> changed = new HashMap<>(original.toMap());
                changed.put("content", replacement);
                assertFalse(new SignedNostrEvent(changed).verify());
                assertFalse(new SignedNostrEvent(changed).verifyAsync().await());
            }
            assertTrue(signer.sign(new UnsignedNostrEvent().withContent("\ud800")).await().verify());
        } finally {
            signer.getKeyPair().destroy();
        }
    }
}
