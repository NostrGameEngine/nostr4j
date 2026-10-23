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

package org.ngengine.nostr4j.nip51;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class TestNip51 {

    private static final List<String> NOTE = List.of("e", "a".repeat(64), "wss://relay.test");
    private static final List<String> ARTICLE = List.of(
        "a",
        "30023:79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798:article:one"
    );

    @Test
    public void globalListsAndNamedSetsCopyTheirReferencesAndKeepRelayHints() {
        List<String> reference = new ArrayList<>(NOTE);
        List<List<String>> items = new ArrayList<>(List.of(reference));
        Nip51BookmarkList bookmarks = bookmarks(items);
        reference.set(1, "c".repeat(64));
        items.clear();
        assertEquals(10003, bookmarks.toUpdateEvent().getKind());
        assertEquals("", bookmarks.toUpdateEvent().getContent());
        assertEquals(List.of(NOTE), bookmarks.getPublicBookmarks());
        assertThrows(UnsupportedOperationException.class, () -> bookmarks.getPublicBookmarks().get(0).set(1, "changed"));
        Nip51BookmarkList set = namedSet("reading", List.of(ARTICLE));
        set.event.withTag("title", "Read later");
        assertEquals(30003, set.toUpdateEvent().getKind());
        assertEquals("reading", set.toUpdateEvent().getFirstTagFirstValue("d"));
        assertEquals("Read later", set.toUpdateEvent().getFirstTagFirstValue("title"));
        assertEquals(List.of(ARTICLE), set.getPublicBookmarks());
    }

    @Test
    public void eventReferencesUseAddressesOnlyForAddressableEvents() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            for (int kind : List.of(1, 10003)) {
                SignedNostrEvent note = signer.sign(new UnsignedNostrEvent().withKind(kind)).await();
                assertEquals(List.of("e", note.getId()), Nip51.reference(note));
            }
            SignedNostrEvent article = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "entry")).await();
            assertEquals(List.of("a", "30023:" + article.getPubkey().asHex() + ":entry"), Nip51.reference(article));
        } finally {
            signer.getKeyPair().destroy();
        }
    }

    @Test
    public void privateReferencesUseNip44AndOnlyTheAuthorCanReadThem() throws Exception {
        NostrKeyPairSigner owner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner other = NostrKeyPairSigner.generate();
        try {
            List<List<String>> privateItems = new ArrayList<>(List.of(ARTICLE, NOTE, ARTICLE));
            Nip51BookmarkList model = namedSet("private", List.of(NOTE));
            model.event.withTag("title", "Private reading");
            model.setPrivateBookmarks(owner, privateItems).await();
            privateItems.clear();
            String content = model.toUpdateEvent().getContent();
            assertFalse(content.isEmpty());
            assertFalse(content.contains(ARTICLE.get(1)));
            assertEquals(
                NGEUtils.getPlatform().toJSON(List.of(ARTICLE, NOTE, ARTICLE)),
                owner.decrypt(content, owner.getPublicKey().await(), NostrSigner.EncryptAlgo.NIP44).await()
            );
            assertEquals(List.of(ARTICLE, NOTE, ARTICLE), model.getPrivateBookmarks(owner).await());
            SignedNostrEvent event = owner.sign(model.toUpdateEvent()).await();
            Nip51BookmarkList loaded = Nip51.from(event);
            assertEquals(List.of(NOTE), loaded.getPublicBookmarks());
            assertEquals(List.of(ARTICLE, NOTE, ARTICLE), loaded.getPrivateBookmarks(owner).await());
            assertThrows(Exception.class, () -> loaded.getPrivateBookmarks(other).await());
            Map<String, Object> tampered = new LinkedHashMap<>(event.toMap());
            tampered.put("content", content + "A");
            assertThrows(Exception.class, () -> Nip51.from(new SignedNostrEvent(tampered)).getPrivateBookmarks(owner).await());
        } finally {
            owner.getKeyPair().destroy();
            other.getKeyPair().destroy();
        }
    }

    @Test
    public void readsLegacyPrivateListsButRejectsMalformedDecryptedReferences() throws Exception {
        NostrKeyPairSigner owner = NostrKeyPairSigner.generate();
        try {
            String legacy = owner
                .encrypt(
                    NGEUtils.getPlatform().toJSON(List.of(NOTE)),
                    owner.getPublicKey().await(),
                    NostrSigner.EncryptAlgo.NIP04
                )
                .await();
            SignedNostrEvent event = owner.sign(bookmarks(List.of()).event.withContent(legacy)).await();
            assertEquals(List.of(NOTE), Nip51.from(event).getPrivateBookmarks(owner).await());
            String invalid = owner.encrypt("[[\"e\",42]]", owner.getPublicKey().await()).await();
            SignedNostrEvent malformed = owner.sign(bookmarks(List.of()).event.withContent(invalid)).await();
            assertThrows(Exception.class, () -> Nip51.from(malformed).getPrivateBookmarks(owner).await());
            Nip51BookmarkList empty = bookmarks(List.of(NOTE));
            empty.setPrivateBookmarks(owner, List.of()).await();
            assertEquals(List.of(), empty.getPrivateBookmarks(owner).await());
        } finally {
            owner.getKeyPair().destroy();
        }
    }

    @Test
    public void validatesReferenceKindsBeforeReplacingExistingData() {
        Nip51BookmarkList model = bookmarks(List.of(NOTE));
        for (List<String> invalid : List.of(
            List.of("e", "invalid"),
            List.of("a", "30023:invalid:x"),
            List.of("p", "a".repeat(64)),
            List.of("a", "+30023:" + ARTICLE.get(1).split(":", 3)[1] + ":x"),
            List.of("a", "999999999999999999999:" + ARTICLE.get(1).split(":", 3)[1] + ":x")
        )) assertThrows(IllegalArgumentException.class, () -> model.setPublicBookmarks(List.of(invalid)));
        assertEquals(List.of(NOTE), model.getPublicBookmarks());
        assertThrows(IllegalArgumentException.class, () -> namedSet(" ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> Nip51.from(new UnsignedNostrEvent().withKind(1)));
        assertThrows(IllegalArgumentException.class, () -> Nip51.from(new UnsignedNostrEvent().withKind(30003)));
    }

    @Test
    public void updateSignsASnapshotAndUsesThePoolsAcknowledgementTask() throws Exception {
        DeferredSigner signer = new DeferredSigner();
        RecordingPool pool = new RecordingPool();
        try {
            Nip51BookmarkList original = bookmarks(List.of(NOTE));
            AsyncTask<List<AsyncTask<NostrMessageAck>>> result = Nip51.update(pool, signer, original);
            original.event.clearTags("e").withContent("Changed after update started");
            signer.finish();
            assertSame(pool.acknowledgements, result.await());
            assertEquals(List.of(NOTE), Nip51.from(pool.published).getPublicBookmarks());
            assertEquals("", pool.published.getContent());
            assertTrue(pool.published.verify());
            assertEquals(1, pool.calls);
        } finally {
            signer.getKeyPair().destroy();
        }
    }

    @Test
    public void signingAndPublicationFailuresPropagateWithoutAnotherPublication() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        RecordingPool pool = new RecordingPool();
        try {
            pool.failure = new IllegalStateException("Fixture relay failure");
            assertThrows(Exception.class, () -> Nip51.update(pool, signer, bookmarks(List.of())).await());
            assertEquals(1, pool.calls);
            NostrKeyPairSigner failing = new NostrKeyPairSigner(signer.getKeyPair()) {
                @Override
                public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
                    return AsyncTask.failed(new IllegalStateException("Fixture signing failure"));
                }
            };
            assertThrows(Exception.class, () -> Nip51.update(pool, failing, bookmarks(List.of())).await());
            assertEquals(1, pool.calls);
        } finally {
            signer.getKeyPair().destroy();
        }
    }

    @Test
    public void publicEditsRetainEncryptedContentAndUnknownMetadata() throws Exception {
        NostrKeyPairSigner owner = NostrKeyPairSigner.generate();
        RecordingPool pool = new RecordingPool();
        try {
            Nip51BookmarkList initial = namedSet("reading", List.of(NOTE));
            initial.event.withTag("title", "A title").withTag("custom", "Keep me");
            initial.setPrivateBookmarks(owner, List.of(ARTICLE)).await();
            SignedNostrEvent source = owner.sign(initial.toUpdateEvent().createdAt(Instant.ofEpochSecond(100))).await();
            Nip51BookmarkList edited = Nip51.from(source);
            edited.setPublicBookmarks(List.of(ARTICLE));
            Nip51.update(pool, owner, edited).await();
            assertEquals(source.getContent(), pool.published.getContent());
            assertEquals("Keep me", pool.published.getFirstTagFirstValue("custom"));
            assertEquals("A title", pool.published.getFirstTagFirstValue("title"));
            assertEquals("reading", pool.published.getFirstTagFirstValue("d"));
            assertTrue(pool.published.getCreatedAt().isAfter(source.getCreatedAt()));
            assertEquals(List.of(NOTE), Nip51.from(source).getPublicBookmarks());
            assertEquals(List.of(ARTICLE), Nip51.from(pool.published).getPrivateBookmarks(owner).await());
        } finally {
            owner.getKeyPair().destroy();
        }
    }

    @Test
    public void updateRejectsChangingTheOwnerOfEncryptedContent() throws Exception {
        NostrKeyPairSigner owner = NostrKeyPairSigner.generate();
        NostrKeyPairSigner other = NostrKeyPairSigner.generate();
        RecordingPool pool = new RecordingPool();
        try {
            Nip51BookmarkList model = bookmarks(List.of());
            model.setPrivateBookmarks(owner, List.of(NOTE)).await();
            assertThrows(Exception.class, () -> Nip51.update(pool, other, model).await());
            Nip51BookmarkList unknown = bookmarks(List.of());
            unknown.event.withContent(model.event.getContent());
            assertThrows(IllegalStateException.class, () -> Nip51.update(pool, owner, unknown));
            assertEquals(0, pool.calls);
        } finally {
            owner.getKeyPair().destroy();
            other.getKeyPair().destroy();
        }
    }

    @Test
    public void fetchUsesGlobalDefaultsAndAcceptsNamedSetFilters() throws Exception {
        NostrKeyPairSigner owner = NostrKeyPairSigner.generate();
        RecordingPool pool = new RecordingPool();
        try {
            assertEquals(Nip51.BOOKMARKS, Nip51.fetch(pool, owner.getPublicKey().await()).await().event.getKind());
            assertEquals(List.of(Nip51.BOOKMARKS), pool.filter.getKinds());
            assertEquals(List.of(owner.getPublicKey().await().asHex()), pool.filter.getAuthors());
            assertEquals(Duration.ofSeconds(21), pool.timeout);
            NostrFilter filter = new NostrFilter()
                .withKind(Nip51.BOOKMARK_SET)
                .withAuthor(owner.getPublicKey().await())
                .withTag("d", "reading");
            assertNull(Nip51.fetch(pool, filter, Duration.ofSeconds(2)).await());
            pool.fetched = List.of(owner.sign(namedSet("reading", List.of(NOTE)).toUpdateEvent()).await());
            assertEquals("reading", Nip51.fetch(pool, filter, Duration.ofSeconds(2)).await().event.getFirstTagFirstValue("d"));
            assertSame(filter, pool.filter);
            assertEquals(Duration.ofSeconds(2), pool.timeout);
        } finally {
            owner.getKeyPair().destroy();
        }
    }

    private static Nip51BookmarkList bookmarks(Collection<List<String>> items) {
        Nip51BookmarkList model = new Nip51BookmarkList();
        model.setPublicBookmarks(items);
        return model;
    }

    private static Nip51BookmarkList namedSet(String identifier, Collection<List<String>> items) {
        Nip51BookmarkList model = Nip51.from(new UnsignedNostrEvent().withKind(Nip51.BOOKMARK_SET).withTag("d", identifier));
        model.setPublicBookmarks(items);
        return model;
    }

    private static final class RecordingPool extends NostrPool {

        private final List<AsyncTask<NostrMessageAck>> acknowledgements = new ArrayList<>();
        private SignedNostrEvent published;
        private int calls;
        private Throwable failure;
        private List<SignedNostrEvent> fetched = List.of();
        private NostrFilter filter;
        private Duration timeout;

        @Override
        public AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent event) {
            published = event;
            calls++;
            return failure == null ? AsyncTask.completed(acknowledgements) : AsyncTask.failed(failure);
        }

        @Override
        public AsyncTask<List<SignedNostrEvent>> fetch(NostrFilter filter, int count, Duration timeout) {
            this.filter = filter;
            this.timeout = timeout;
            assertEquals(1, count);
            return AsyncTask.completed(fetched);
        }
    }

    private static final class DeferredSigner extends NostrKeyPairSigner {

        private UnsignedNostrEvent pending;
        private volatile Consumer<SignedNostrEvent> complete;

        private DeferredSigner() {
            super(new NostrKeyPair());
        }

        @Override
        public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
            pending = event;
            return AsyncTask.create((resolve, reject) -> complete = resolve);
        }

        private void finish() throws Exception {
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (complete == null && System.nanoTime() < deadline) Thread.sleep(1);
            assertNotNull(complete);
            complete.accept(super.sign(pending).await());
        }
    }
}
