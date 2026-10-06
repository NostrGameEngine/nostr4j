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
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

public class TestSignedEventEncodingCache {

    private NGEPlatform previous;
    private CountingPlatform platform;

    private static class CountingPlatform extends JVMAsyncPlatform {

        int hashes;
        int encodings;

        @Override
        public String sha256(String value) {
            hashes++;
            return super.sha256(value);
        }

        @Override
        public String toJSON(Map value) {
            encodings++;
            return super.toJSON(value);
        }
    }

    @Before
    public void setUp() throws Exception {
        previous = NGEUtils.getPlatform();
        platform = new CountingPlatform();
        replacePlatform(platform);
    }

    @After
    public void tearDown() throws Exception {
        replacePlatform(previous);
    }

    private static void replacePlatform(NGEPlatform platform) throws Exception {
        // Test-only provider substitution; production platforms remain single-assignment.
        Field field = NGEPlatform.class.getDeclaredField("platform");
        field.setAccessible(true);
        field.set(null, platform);
    }

    private SignedNostrEvent event() {
        return new SignedNostrEvent(
            Map.of(
                "id",
                "0".repeat(64),
                "pubkey",
                "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
                "kind",
                1,
                "content",
                "cache test",
                "created_at",
                1700000000L,
                "sig",
                "0".repeat(128),
                "tags",
                List.of(List.of("t", "first"))
            )
        );
    }

    @Test
    public void typedJsonParsingMatchesMapConstruction() throws Exception {
        SignedNostrEvent event = event();
        SignedNostrEvent parsed = SignedNostrEvent.fromJSON(event.toEventJSON());
        assertEquals(event.toMap(), parsed.toMap());
        assertEquals(
            NostrEvent.computeEventIdUncached(event.getPubkey().asHex(), event),
            NostrEvent.computeEventId(event.getPubkey().asHex(), parsed)
        );
        assertFalse(parsed.verify());
    }

    @Test
    public void cachesComputedHashWithoutTrustingDeclaredId() throws Exception {
        SignedNostrEvent event = event();
        String pubkey = event.getPubkey().asHex();
        String first = NostrEvent.computeEventId(pubkey, event);
        assertNotEquals(event.getId(), first);
        assertSame(first, NostrEvent.computeEventId(pubkey, event));
        assertEquals(1, platform.hashes);
        assertFalse(event.verify());
        assertFalse(event.verifyAsync().await());
        assertEquals(1, platform.hashes);
        String other = NostrEvent.computeEventId("1".repeat(64), event);
        assertNotEquals(first, other);
        assertEquals(first, NostrEvent.computeEventId(pubkey, event));
        assertEquals(3, platform.hashes);
    }

    @Test
    public void keepsPlatformIdentityInHashAndJsonCaches() throws Exception {
        SignedNostrEvent event = event();
        String pubkey = event.getPubkey().asHex();
        String hash = NostrEvent.computeEventId(pubkey, event);
        String json = event.toEventJSON();
        CountingPlatform replacement = new CountingPlatform();
        replacePlatform(replacement);
        assertEquals(hash, NostrEvent.computeEventId(pubkey, event));
        assertEquals(json, event.toEventJSON());
        assertEquals(1, replacement.hashes);
        assertEquals(1, replacement.encodings);
    }

    @Test
    public void cachedJsonIsAnEventObjectAndKeepsRowsImmutable() {
        SignedNostrEvent event = event();
        String json = event.toEventJSON();
        assertSame(json, event.toEventJSON());
        assertEquals(1, platform.encodings);
        Map<?, ?> parsed = platform.fromJSON(json, Map.class);
        assertEquals(event.getId(), parsed.get("id"));
        assertEquals(event.getTagRows(), parsed.get("tags"));
        try {
            event.getTagRows().get(0).set(1, "tamper");
            fail("Mutable tag row escaped");
        } catch (UnsupportedOperationException expected) {
            assertEquals(json, event.toEventJSON());
        }
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void rawMutableTagLeafBypassesJsonCache() {
        SignedNostrEvent source = event();
        StringBuilder leaf = new StringBuilder("first");
        List rawRows = List.of(List.of("t", leaf));
        SignedNostrEvent event = new SignedNostrEvent(
            source.getId(),
            source.getPubkey(),
            source.getKind(),
            source.getContent(),
            source.getCreatedAt(),
            source.getSignature(),
            rawRows
        );
        String first = event.toEventJSON();
        leaf.append(" changed");
        assertNotEquals(first, event.toEventJSON());
        assertEquals(2, platform.encodings);
    }

    @Test
    public void nullEventKeepsUncachedFailureBehavior() {
        assertNull(NostrEvent.computeEventId("0".repeat(64), null));
        assertEquals(0, platform.hashes);
    }

    @Test
    public void encodingCachesAreNotJavaSerialized() throws Exception {
        SignedNostrEvent original = event();
        String hash = NostrEvent.computeEventId(original.getPubkey().asHex(), original);
        String json = original.toEventJSON();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        try (
            java.io.ObjectInputStream input = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray())
            )
        ) {
            SignedNostrEvent restored = (SignedNostrEvent) input.readObject();
            assertEquals(hash, NostrEvent.computeEventId(restored.getPubkey().asHex(), restored));
            assertEquals(json, restored.toEventJSON());
        }
        assertEquals(2, platform.hashes);
        assertEquals(2, platform.encodings);
    }

    @Test
    public void preparedFilterOwnsRulesAndPreservesMatchingSemantics() {
        SignedNostrEvent event = event();
        org.ngengine.nostr4j.NostrFilter filter = new org.ngengine.nostr4j.NostrFilter()
            .withAuthor(event.getPubkey())
            .withKind(1)
            .withTag("t", "wrong", "first");
        java.util.function.Predicate<SignedNostrEvent> prepared = filter.prepare();
        assertEquals(filter.matches(event), prepared.test(event));
        filter.getTags().get("t").set(1, "changed");
        filter.getAuthors().clear();
        assertFalse(filter.matches(event));
        assertTrue(prepared.test(event));
        org.ngengine.nostr4j.NostrFilter limited = new org.ngengine.nostr4j.NostrFilter().limit(0);
        assertFalse(limited.prepare().test(event));
        org.ngengine.nostr4j.NostrFilter any = new org.ngengine.nostr4j.NostrFilter().withTag("t", "first");
        assertEquals(any.matches(event, true), any.prepare(true).test(event));
        assertFalse(new org.ngengine.nostr4j.NostrFilter().withTag("missing", "x").prepare().test(event));
        assertFalse(new org.ngengine.nostr4j.NostrFilter().withTag("t").prepare().test(event));
    }

    @Test
    public void mutableSubclassBypassesHashAndJsonCaches() {
        SignedNostrEvent source = event();
        String[] body = { "first" };
        SignedNostrEvent subclass = new SignedNostrEvent(source.toMap()) {
            @Override
            public String getContent() {
                return body[0];
            }

            @Override
            public Map<String, Object> toMap() {
                return Map.of("content", body[0]);
            }
        };
        String pubkey = source.getPubkey().asHex();
        String firstHash = NostrEvent.computeEventId(pubkey, subclass);
        String firstJson = subclass.toEventJSON();
        body[0] = "second";
        assertNotEquals(firstHash, NostrEvent.computeEventId(pubkey, subclass));
        assertNotEquals(firstJson, subclass.toEventJSON());
        assertEquals(2, platform.hashes);
        assertEquals(2, platform.encodings);
    }
}
