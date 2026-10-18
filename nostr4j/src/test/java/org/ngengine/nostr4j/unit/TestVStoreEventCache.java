/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.store.NostrCacheRelay;
import org.ngengine.nostr4j.store.NostrEventCache;
import org.ngengine.nostr4j.store.NostrVStoreEventStore;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;
import org.ngengine.platform.jvm.FileSystemVStore;

public class TestVStoreEventCache {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private static final class Backend implements VStore.VStoreBackend {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final AtomicInteger payloadReads = new AtomicInteger();
        final AtomicInteger listings = new AtomicInteger();
        volatile boolean failWrites;
        volatile boolean failIndexWrites;

        @Override
        public AsyncTask<InputStream> read(String path) {
            if (path.contains("/events/")) payloadReads.incrementAndGet();
            byte[] value = files.get(path);
            return value == null ? AsyncTask.failed(new IOException("Missing entry"))
                : AsyncTask.completed(new ByteArrayInputStream(value));
        }

        @Override
        public AsyncTask<OutputStream> write(String path) {
            return AsyncTask.completed(new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    if (failWrites || failIndexWrites && path.contains("/index/") && path.endsWith(".json")) throw new IOException("Injected write failure");
                    files.put(path, toByteArray());
                }
            });
        }

        @Override
        public AsyncTask<Boolean> exists(String path) {
            return AsyncTask.completed(files.containsKey(path));
        }

        @Override
        public AsyncTask<Void> delete(String path) {
            files.remove(path);
            return AsyncTask.completed(null);
        }

        @Override
        public AsyncTask<List<String>> listAll() {
            listings.incrementAndGet();
            return AsyncTask.completed(new ArrayList<>(files.keySet()));
        }
    }

    private static String payload(String id) {
        return "nostr-event-cache/v1/events/" + id.substring(0, 2) + "/" + id.substring(2, 4) + "/" + id + ".json";
    }

    private static SignedNostrEvent note(NostrKeyPairSigner signer, String content) throws Exception {
        return signer.sign(new UnsignedNostrEvent().withKind(1).withContent(content)).await();
    }

    @Test
    public void coldIdLookupUsesShardsWithoutListingAndMemoryEvictionPreservesBacking() throws Exception {
        Backend backend = new Backend();
        VStore store = new VStore(backend);
        NostrEventCache cache = new NostrEventCache(new NostrVStoreEventStore(store, 1));
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent first = note(signer, "First");
            SignedNostrEvent second = note(signer, "Second");
            cache.put(first.getId(), first).await();
            cache.put(second.getId(), second).await();
            assertTrue(backend.files.containsKey(payload(first.getId())));
            assertSame(second, cache.get(second.getId()).await());
            assertEquals(0, backend.payloadReads.get());
            assertEquals(first.getId(), cache.get(first.getId()).await().getId());
            assertEquals(1, backend.payloadReads.get());
            assertEquals(second.getId(), cache.get(second.getId()).await().getId());
            assertEquals(2, backend.payloadReads.get());
            NostrEventCache reopened = new NostrEventCache(new NostrVStoreEventStore(store, 0));
            assertEquals(first.getId(), reopened.get(first.getId()).await().getId());
            assertEquals(0, backend.listings.get());
            assertNull(reopened.get("0".repeat(64)).await());
            assertEquals(0, backend.listings.get());
            assertThrows(Exception.class, () -> reopened.get("../escape").await());
        } finally { signer.close(); }
    }

    @Test
    public void receiptTimeSurvivesReopeningAndDuplicateInsertionDoesNotRenewTtl() throws Exception {
        Backend backend = new Backend();
        VStore store = new VStore(backend);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        NostrEventCache cache = new NostrEventCache(new NostrVStoreEventStore(store, 1), 4096, now::get);
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            cache.setTtl(Duration.ofSeconds(120)).await();
            SignedNostrEvent event = note(signer, "Original lifetime");
            cache.put(event.getId(), event).await();
            now.set(now.get().plusSeconds(60));
            cache.put(event.getId(), event).await();
            NostrEventCache reopened = new NostrEventCache(new NostrVStoreEventStore(store, 0), 4096, now::get);
            reopened.setTtl(Duration.ofSeconds(120)).await();
            assertNotNull(reopened.get(event.getId()).await());
            now.set(now.get().plusSeconds(61));
            assertNull(reopened.get(event.getId()).await());
            assertFalse(backend.files.containsKey(payload(event.getId())));
        } finally { signer.close(); }
    }

    @Test
    public void deletionHistorySurvivesClearAndReopeningWithoutDeletingUnrelatedFiles() throws Exception {
        Backend backend = new Backend();
        backend.files.put("caller-owned.txt", new byte[] { 1, 2, 3 });
        VStore store = new VStore(backend);
        NostrEventCache cache = new NostrEventCache(new NostrVStoreEventStore(store, 1));
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            cache.setHonorDeletions(true).await();
            SignedNostrEvent event = note(signer, "Deleted by author");
            SignedNostrEvent deletion = signer.sign(new UnsignedNostrEvent().withKind(5).withTag("e", event.getId())).await();
            cache.put(event.getId(), event).await();
            cache.put(deletion.getId(), deletion).await();
            assertNull(cache.get(event.getId()).await());
            cache.clear().await();
            NostrEventCache reopened = new NostrEventCache(new NostrVStoreEventStore(store, 0));
            reopened.setHonorDeletions(true).await();
            assertTrue(reopened.isDeleted(event).await());
            reopened.put(event.getId(), event).await();
            assertNull(reopened.get(event.getId()).await());
            assertEquals(0, reopened.size().await().intValue());
            assertArrayEquals(new byte[] { 1, 2, 3 }, backend.files.get("caller-owned.txt"));
        } finally { signer.close(); }
    }

    @Test
    public void filterQueriesHonorLatestVersionsAndLimitsAcrossColdPayloads() throws Exception {
        Backend backend = new Backend();
        VStore store = new VStore(backend);
        NostrEventCache cache = new NostrEventCache(new NostrVStoreEventStore(store, 1));
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            cache.setReplaceableTtl(null).await();
            SignedNostrEvent old = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "article")
                .createdAt(Instant.now().minusSeconds(10)).withContent("Old")).await();
            SignedNostrEvent latest = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "article")
                .withContent("Latest")).await();
            SignedNostrEvent note = note(signer, "Note");
            for (SignedNostrEvent event : List.of(old, latest, note)) cache.put(event.getId(), event).await();
            NostrEventCache reopened = new NostrEventCache(new NostrVStoreEventStore(store, 0));
            reopened.setReplaceableTtl(null).await();
            assertEquals(List.of(latest.getId()), reopened.query(List.of(new NostrFilter().withKind(30023))).await()
                .stream().map(SignedNostrEvent::getId).collect(java.util.stream.Collectors.toList()));
            assertEquals(old.getId(), reopened.query(List.of(new NostrFilter().withId(old.getId()))).await().get(0).getId());
            assertEquals(1, reopened.query(List.of(new NostrFilter().limit(1))).await().size());
            assertTrue(reopened.query(List.of(new NostrFilter().limit(0))).await().isEmpty());
            assertEquals(3, reopened.size().await().intValue());
        } finally { signer.close(); }
    }

    @Test
    public void failedBackingWritesAreVisibleAndDoNotPublishMemoryOnlySuccess() throws Exception {
        Backend backend = new Backend();
        VStore store = new VStore(backend);
        NostrEventCache cache = new NostrEventCache(new NostrVStoreEventStore(store, 2));
        AtomicInteger notifications = new AtomicInteger();
        cache.observe(event -> notifications.incrementAndGet());
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent event = note(signer, "Write-through");
            backend.failWrites = true;
            assertThrows(Exception.class, () -> cache.put(event.getId(), event).await());
            assertEquals(0, notifications.get());
            assertNull(cache.get(event.getId()).await());
            backend.failWrites = false;
            cache.put(event.getId(), event).await();
            assertEquals(1, notifications.get());
            assertNotNull(new NostrEventCache(new NostrVStoreEventStore(store, 0)).get(event.getId()).await());
        } finally { signer.close(); }
    }

    @Test
    public void coldPayloadsAreReauthenticatedBeforeDelivery() throws Exception {
        Backend backend = new Backend();
        VStore store = new VStore(backend);
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent event = note(signer, "Authentic");
            new NostrEventCache(new NostrVStoreEventStore(store, 0)).put(event.getId(), event).await();
            Map<String, Object> envelope = NGEPlatform.get().fromJSON(
                new String(backend.files.get(payload(event.getId())), StandardCharsets.UTF_8), Map.class);
            Map<String, Object> modified = new LinkedHashMap<>((Map<String, Object>) envelope.get("event"));
            modified.put("content", "Changed after insertion");
            envelope.put("event", modified);
            backend.files.put(payload(event.getId()), NGEPlatform.get().toJSON(envelope).getBytes(StandardCharsets.UTF_8));
            assertThrows(Exception.class, () -> new NostrEventCache(new NostrVStoreEventStore(store, 0)).get(event.getId()).await());
        } finally { signer.close(); }
    }

    @Test
    public void realFileSystemStoreCanBeReopenedAndFeedsOrdinaryFetchPolicies() throws Exception {
        VStore store = new VStore(new FileSystemVStore(temporary.newFolder().toPath()));
        NostrEventCache writer = new NostrEventCache(new NostrVStoreEventStore(store, 1));
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrPool pool = new NostrPool();
        NostrCacheRelay relay = new NostrCacheRelay(new NostrEventCache(new NostrVStoreEventStore(store, 0)));
        try {
            SignedNostrEvent event = note(signer, "Persistent relay result");
            writer.put(event.getId(), event).await();
            pool.addRelay(relay).await();
            List<SignedNostrEvent> results = pool.fetch(new NostrFilter().withId(event.getId()), 10, true, Duration.ofSeconds(2)).await();
            assertEquals(1, results.size());
            assertEquals(event.getId(), results.get(0).getId());
        } finally { pool.clean(); relay.close(); signer.close(); }
    }

    @Test
    public void indexedFiltersReadOnlyTheirResultsAfterReopening() throws Exception {
        Backend backend = new Backend();
        VStore files = new VStore(backend);
        NostrVStoreEventStore writer = new NostrVStoreEventStore(files, 0);
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrKeyPairSigner other = NostrKeyPairSigner.generate();
        try {
            List<SignedNostrEvent> expected = new ArrayList<>();
            Instant start = Instant.now().minusSeconds(100);
            for (int i = 0; i < 60; i++) {
                NostrKeyPairSigner author = i % 2 == 0 ? signer : other;
                SignedNostrEvent event = author.sign(new UnsignedNostrEvent().withKind(i % 3 == 0 ? 1 : 2)
                    .withTag("t", i % 5 == 0 ? "wanted" : "other").createdAt(start.plusSeconds(i)).withContent("Event " + i)).await();
                writer.addEvent(event).await();
                if (i % 2 == 0 && i % 3 == 0 && i % 5 == 0) expected.add(event);
            }
            expected.sort(org.ngengine.nostr4j.store.NostrEventStore.ORDER);
            NostrEventCache reader = new NostrEventCache(new NostrVStoreEventStore(files, 0));
            NostrFilter filter = new NostrFilter().withAuthor(signer.getPublicKey().await()).withKind(1)
                .withTag("t", "wanted").since(start).until(start.plusSeconds(59)).limit(1);
            backend.payloadReads.set(0);
            backend.listings.set(0);
            assertEquals(expected.get(0).getId(), reader.query(List.of(filter)).await().get(0).getId());
            assertEquals("Only the returned payload should be read", 1, backend.payloadReads.get());
            assertEquals(1, backend.listings.get());
            backend.payloadReads.set(0);
            backend.listings.set(0);
            assertEquals(expected.get(0).getId(), reader.query(List.of(filter)).await().get(0).getId());
            assertEquals(1, backend.payloadReads.get());
            assertEquals("The loaded index should avoid another listing", 0, backend.listings.get());
            backend.payloadReads.set(0);
            assertTrue(reader.query(List.of(filter.clone().limit(0))).await().isEmpty());
            assertEquals(0, backend.payloadReads.get());
        } finally { signer.close(); other.close(); }
    }

    @Test
    public void missingMalformedAndInterruptedIndexesAreRebuilt() throws Exception {
        Backend backend = new Backend();
        VStore files = new VStore(backend);
        NostrVStoreEventStore writer = new NostrVStoreEventStore(files, 0);
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            List<SignedNostrEvent> saved = new ArrayList<>();
            Instant start = Instant.now().minusSeconds(30);
            for (int i = 0; i < 12; i++) {
                SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Recovery " + i)
                    .createdAt(start.plusSeconds(i))).await();
                saved.add(event);
                writer.addEvent(event).await();
            }
            for (int scenario = 0; scenario < 3; scenario++) {
                if (scenario == 0) backend.files.keySet().removeIf(path -> path.contains("/index/") || path.endsWith("index-state.json"));
                if (scenario == 1) {
                    String bucket = backend.files.keySet().stream().filter(path -> path.contains("/index/") && path.endsWith(".json")).findFirst().orElseThrow();
                    backend.files.put(bucket, "invalid JSON".getBytes(StandardCharsets.UTF_8));
                }
                if (scenario == 2) backend.files.put("nostr-event-cache/v1/index/dirty", new byte[] { 1 });
                NostrVStoreEventStore reader = new NostrVStoreEventStore(files, 0);
                backend.payloadReads.set(0);
                assertEquals(saved.get(11).getId(), reader.query(List.of(new NostrFilter().limit(1))).await().get(0).getId());
                assertEquals("Rebuild reads each payload once, then reads the result", 13, backend.payloadReads.get());
                backend.payloadReads.set(0);
                backend.listings.set(0);
                assertEquals(1, reader.query(List.of(new NostrFilter().limit(1))).await().size());
                assertEquals(1, backend.payloadReads.get());
                assertEquals(0, backend.listings.get());
            }
        } finally { signer.close(); }
    }

    @Test
    public void failedIndexWriteIsRecoveredWithoutLosingTheSavedEvent() throws Exception {
        Backend backend = new Backend();
        VStore files = new VStore(backend);
        NostrEventCache cache = new NostrEventCache(new NostrVStoreEventStore(files, 0));
        AtomicInteger notifications = new AtomicInteger();
        cache.observe(event -> notifications.incrementAndGet());
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent first = note(signer, "Before interruption");
            SignedNostrEvent second = note(signer, "Saved before index failure");
            cache.put(first.getId(), first).await();
            backend.failIndexWrites = true;
            assertThrows(Exception.class, () -> cache.put(second.getId(), second).await());
            assertEquals(1, notifications.get());
            assertTrue(backend.files.containsKey(payload(second.getId())));
            backend.failIndexWrites = false;
            NostrEventCache reopened = new NostrEventCache(new NostrVStoreEventStore(files, 0));
            assertEquals(2, reopened.query(List.of(new NostrFilter())).await().size());
            assertFalse(backend.files.containsKey("nostr-event-cache/v1/index/dirty"));
        } finally { signer.close(); }
    }

    @Test
    public void incrementalCleanupChecksOnlyItsBatchAndDoesNotReadPayloads() throws Exception {
        Backend backend = new Backend();
        VStore files = new VStore(backend);
        NostrVStoreEventStore store = new NostrVStoreEventStore(files, 0);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        NostrEventCache cache = new NostrEventCache(store, 64, now::get);
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            cache.setTtl(Duration.ofSeconds(10)).await();
            for (int i = 0; i < 23; i++) {
                SignedNostrEvent event = note(signer, "Cleanup " + i);
                cache.put(event.getId(), event).await();
            }
            now.set(now.get().plusSeconds(11));
            backend.payloadReads.set(0);
            assertEquals(7, cache.clean(7).await().intValue());
            assertEquals(16, store.size().await().intValue());
            assertEquals(7, cache.clean(7).await().intValue());
            assertEquals(7, cache.clean(7).await().intValue());
            assertEquals(2, cache.clean(7).await().intValue());
            assertEquals(0, store.size().await().intValue());
            assertEquals("Expiry metadata should be enough for cleanup", 0, backend.payloadReads.get());
        } finally { signer.close(); }
    }

    @Test
    public void loadedReadersNoticeLaterWritesAndClearKeepsUnrelatedFiles() throws Exception {
        Backend backend = new Backend();
        VStore files = new VStore(backend);
        NostrVStoreEventStore writer = new NostrVStoreEventStore(files, 0);
        NostrVStoreEventStore reader = new NostrVStoreEventStore(files, 0);
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent first = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("First")
                .createdAt(Instant.now().minusSeconds(10))).await();
            SignedNostrEvent second = note(signer, "Later write");
            writer.addEvent(first).await();
            assertEquals(first.getId(), reader.query(List.of(new NostrFilter().limit(1))).await().get(0).getId());
            writer.addEvent(second).await();
            backend.payloadReads.set(0);
            assertEquals(second.getId(), reader.query(List.of(new NostrFilter().limit(1))).await().get(0).getId());
            assertEquals(1, backend.payloadReads.get());
            backend.files.put("nostr-event-cache/v1/events/caller.txt", new byte[] { 1 });
            backend.files.put("nostr-event-cache/v1/index/caller.txt", new byte[] { 2 });
            writer.clear().await();
            assertTrue(reader.query(List.of(new NostrFilter())).await().isEmpty());
            assertArrayEquals(new byte[] { 1 }, backend.files.get("nostr-event-cache/v1/events/caller.txt"));
            assertArrayEquals(new byte[] { 2 }, backend.files.get("nostr-event-cache/v1/index/caller.txt"));
        } finally { signer.close(); }
    }

    @Test
    public void incrementalCleanupMovesPastLiveEntries() throws Exception {
        Backend backend = new Backend();
        VStore files = new VStore(backend);
        NostrVStoreEventStore store = new NostrVStoreEventStore(files, 0);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        NostrEventCache cache = new NostrEventCache(store, 64, now::get);
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            cache.setTtl(Duration.ofSeconds(10)).await();
            List<SignedNostrEvent> events = new ArrayList<>();
            for (int i = 0; i < 11; i++) events.add(note(signer, "Mixed cleanup " + i));
            events.sort(java.util.Comparator.comparing(SignedNostrEvent::getId));
            for (int i = 0; i < events.size(); i++) {
                SignedNostrEvent event = events.get(i);
                Instant received = i < 4 ? now.get() : now.get().minusSeconds(20);
                store.writeEntry(event.getId(), new org.ngengine.nostr4j.store.NostrEventStore.Entry(event, received)).await();
            }
            backend.payloadReads.set(0);
            assertEquals(0, cache.clean(4).await().intValue());
            assertEquals(4, cache.clean(4).await().intValue());
            assertEquals(3, cache.clean(4).await().intValue());
            assertEquals(4, store.size().await().intValue());
            assertEquals(0, backend.payloadReads.get());
        } finally { signer.close(); }
    }
}
