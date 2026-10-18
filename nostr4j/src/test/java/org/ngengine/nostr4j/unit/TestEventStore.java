package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.store.NostrEventCache;
import org.ngengine.nostr4j.store.NostrEventStore;
import org.ngengine.nostr4j.store.NostrInMemoryEventStore;
import org.ngengine.nostr4j.store.NostrVStoreEventStore;
import org.ngengine.nostr4j.store.NostrWeakInMemoryEventStore;
import org.ngengine.platform.VStore;
import org.ngengine.platform.jvm.FileSystemVStore;

@RunWith(Parameterized.class)
public class TestEventStore {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> backends() {
        return Arrays.asList(new Object[][] { { "memory" }, { "weak" }, { "vstore-cold" }, { "vstore-hot" } });
    }

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();
    private final String backend;
    private NostrEventStore store;
    private VStore files;

    public TestEventStore(String backend) {
        this.backend = backend;
    }

    @Before
    public void createStore() throws Exception {
        if (backend.equals("memory")) store = new NostrInMemoryEventStore(8);
        else if (backend.equals("weak")) store = new NostrWeakInMemoryEventStore(8);
        else {
            files = new VStore(new FileSystemVStore(temporary.newFolder().toPath()));
            store = new NostrVStoreEventStore(files, backend.equals("vstore-hot") ? 1 : 0);
        }
    }

    private SignedNostrEvent note(NostrKeyPairSigner signer, String content, Instant created) throws Exception {
        return signer.sign(new UnsignedNostrEvent().withKind(1).withContent(content).createdAt(created)).await();
    }

    @Test
    public void duplicateIdsKeepReceiptTimesAndRemovalReturnsThePayload() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent event = note(signer, "Stored once", Instant.now().minusSeconds(1));
            Instant received = Instant.now().minusSeconds(60);
            store.writeEntry(event.getId(), new NostrEventStore.Entry(event, received)).await();
            store.addEvent(event).await();
            assertEquals(received, store.readEntry(event.getId()).await().received);
            assertEquals(1, store.size().await().intValue());
            assertEquals(event.getId(), store.removeEntry(event.getId()).await().event.getId());
            assertNull(store.get(event.getId()).await());
            assertNull(store.removeEntry(event.getId()).await());
            assertThrows(Exception.class, () -> store.writeEntry("0".repeat(64), new NostrEventStore.Entry(event, received)).await());
        } finally { signer.close(); }
    }

    @Test
    public void queriesShareOrderingLimitsDeduplicationAndResultAccumulation() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            Instant now = Instant.now();
            SignedNostrEvent first = note(signer, "Oldest", now.minusSeconds(3));
            SignedNostrEvent middle = note(signer, "Middle", now.minusSeconds(2));
            SignedNostrEvent last = note(signer, "Newest", now.minusSeconds(1));
            for (SignedNostrEvent event : List.of(first, last, middle, first)) store.addEvent(event).await();
            List<SignedNostrEvent> selected = store.query(List.of(new NostrFilter().limit(2), new NostrFilter().withId(last.getId()))).await();
            assertEquals(List.of(last.getId(), middle.getId()), selected.stream().map(SignedNostrEvent::getId).toList());
            assertTrue(store.query(List.of(new NostrFilter().limit(0))).await().isEmpty());
            List<SignedNostrEvent> results = new ArrayList<>();
            results.add(first);
            assertSame(results, store.getEvents(List.of(new NostrFilter().withId(last.getId())), results).await());
            assertEquals(List.of(first.getId(), last.getId()), results.stream().map(SignedNostrEvent::getId).toList());
        } finally { signer.close(); }
    }

    @Test
    public void cacheUsesTheSuppliedStoreAndPreservesReceiptTimeAcrossReconstruction() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
            SignedNostrEvent event = note(signer, "Shared payload", now.get().minusSeconds(1));
            store.writeEntry(event.getId(), new NostrEventStore.Entry(event, now.get())).await();
            NostrEventCache cache = new NostrEventCache(store, 32, now::get);
            assertSame(store, cache.getStore());
            cache.setTtl(Duration.ofSeconds(60)).await();
            assertEquals(event.getId(), cache.get(event.getId()).await().getId());
            now.set(now.get().plusSeconds(61));
            NostrEventStore reopened = files == null ? store : new NostrVStoreEventStore(files, 0);
            NostrEventCache next = new NostrEventCache(reopened, 32, now::get);
            next.setTtl(Duration.ofSeconds(60)).await();
            assertNull(next.get(event.getId()).await());
            assertNull(reopened.get(event.getId()).await());
        } finally { signer.close(); }
    }

    @Test
    public void cacheAuthenticatesEventsInsertedDirectlyIntoItsStore() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent event = note(signer, "Authentic", Instant.now().minusSeconds(1));
            java.util.Map<String, Object> altered = new java.util.LinkedHashMap<>(event.toMap());
            altered.put("content", "Tampered");
            SignedNostrEvent invalid = new SignedNostrEvent(altered);
            store.addEvent(invalid).await();
            NostrEventCache cache = new NostrEventCache(store);
            assertThrows(Exception.class, () -> cache.get(invalid.getId()).await());
        } finally { signer.close(); }
    }

    @Test
    public void metadataSurvivesClearAndCacheDeletionHistorySurvivesReconstruction() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            byte[] metadata = { 1, 2, 3 };
            store.writeMetadata("caller", metadata).await();
            metadata[0] = 9;
            byte[] read = store.readMetadata("caller").await();
            read[1] = 9;
            assertArrayEquals(new byte[] { 1, 2, 3 }, store.readMetadata("caller").await());
            SignedNostrEvent event = note(signer, "Deleted", Instant.now().minusSeconds(1));
            SignedNostrEvent deletion = signer.sign(new UnsignedNostrEvent().withKind(5).withTag("e", event.getId())).await();
            NostrEventCache cache = new NostrEventCache(store);
            cache.put(event).await();
            cache.put(deletion).await();
            cache.clear().await();
            NostrEventStore reopened = files == null ? store : new NostrVStoreEventStore(files, 0);
            NostrEventCache next = new NostrEventCache(reopened);
            assertTrue(next.isDeleted(event).await());
            next.put(event).await();
            assertNull(next.get(event.getId()).await());
            assertArrayEquals(new byte[] { 1, 2, 3 }, reopened.readMetadata("caller").await());
        } finally { signer.close(); }
    }

    @Test
    public void latestVersionIsChosenBeforeApplyingTagsAndTimeBounds() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            Instant now = Instant.now();
            SignedNostrEvent old = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "article")
                .withTag("t", "wanted").createdAt(now.minusSeconds(10))).await();
            SignedNostrEvent latest = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "article")
                .withTag("t", "other").createdAt(now.minusSeconds(1))).await();
            store.addEvent(old).await();
            store.addEvent(latest).await();
            assertTrue(store.query(List.of(new NostrFilter().withKind(30023).withTag("t", "wanted"))).await().isEmpty());
            assertTrue(store.query(List.of(new NostrFilter().withKind(30023).until(old.getCreatedAt()))).await().isEmpty());
            assertEquals(old.getId(), store.query(List.of(new NostrFilter().withId(old.getId()))).await().get(0).getId());
        } finally { signer.close(); }
    }

    @Test
    public void expiredLatestVersionDoesNotHideAnEligibleEarlierVersion() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            Instant now = Instant.now();
            NostrEventCache cache = new NostrEventCache(store, 32, () -> now);
            cache.setReplaceableTtl(Duration.ofSeconds(10)).await();
            SignedNostrEvent old = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "article")
                .withTag("t", "wanted").createdAt(now.minusSeconds(30))).await();
            SignedNostrEvent latest = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "article")
                .withTag("t", "other").createdAt(now.minusSeconds(20))).await();
            store.writeEntry(old.getId(), new NostrEventStore.Entry(old, now)).await();
            store.writeEntry(latest.getId(), new NostrEventStore.Entry(latest, now.minusSeconds(20))).await();
            assertEquals(old.getId(), cache.query(List.of(new NostrFilter().withKind(30023).withTag("t", "wanted").limit(1)))
                .await().get(0).getId());
            assertNotNull("The query should not need to delete skipped events", store.get(latest.getId()).await());
        } finally { signer.close(); }
    }

    @Test
    public void equalTimestampsUseIdOrderAndLimitsApplyToEachFilter() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            Instant time = Instant.now().minusSeconds(1);
            SignedNostrEvent first = note(signer, "Tie one", time);
            SignedNostrEvent second = note(signer, "Tie two", time);
            store.addEvent(first).await();
            store.addEvent(second).await();
            List<SignedNostrEvent> expected = new ArrayList<>(List.of(first, second));
            expected.sort(NostrEventStore.ORDER);
            assertEquals(expected.get(0).getId(), store.query(List.of(new NostrFilter().limit(1))).await().get(0).getId());
            assertEquals(2, store.query(List.of(new NostrFilter().limit(1), new NostrFilter().withId(expected.get(1).getId()).limit(1)))
                .await().size());
            assertEquals(1, store.query(List.of(new NostrFilter().limit(1), new NostrFilter().limit(1))).await().size());
        } finally { signer.close(); }
    }

    @Test
    public void memoryQueriesStopReadingOnceTheirLimitIsFilled() throws Exception {
        class CountingStore extends NostrInMemoryEventStore {
            int reads;
            @Override
            public org.ngengine.platform.AsyncTask<NostrEventStore.Entry> readEntry(String id) {
                reads++;
                return super.readEntry(id);
            }
        }
        CountingStore memory = new CountingStore();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            List<SignedNostrEvent> retained = new ArrayList<>();
            Instant time = Instant.now().minusSeconds(100);
            for (int i = 0; i < 40; i++) {
                SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(i % 2 == 0 ? 1 : 2)
                    .withTag("t", i % 4 == 0 ? "wanted" : "other").createdAt(time.plusSeconds(i)).withContent("Indexed " + i)).await();
                retained.add(event);
                memory.addEvent(event).await();
            }
            assertEquals(3, memory.query(List.of(new NostrFilter().withKind(1).withTag("t", "wanted").limit(3))).await().size());
            assertEquals(3, memory.reads);
            memory.reads = 0;
            assertTrue(memory.query(List.of(new NostrFilter().withKind(99).limit(1))).await().isEmpty());
            assertEquals(0, memory.reads);
            memory.setCapacity(1).await();
            assertTrue(memory.query(List.of(new NostrFilter().withId(retained.get(0).getId()))).await().isEmpty());
            assertEquals(1, memory.size().await().intValue());
        } finally { signer.close(); }
    }

    @Test
    public void collectedLatestReferenceDoesNotHideAnEarlierLiveReference() throws Exception {
        class CollectibleStore extends NostrInMemoryEventStore {
            final List<java.lang.ref.WeakReference<SignedNostrEvent>> references = new ArrayList<>();
            @Override
            protected java.util.function.Supplier<SignedNostrEvent> retain(SignedNostrEvent event) {
                java.lang.ref.WeakReference<SignedNostrEvent> reference = new java.lang.ref.WeakReference<>(event);
                references.add(reference);
                return reference::get;
            }
        }
        CollectibleStore memory = new CollectibleStore();
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent old = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "article")
                .createdAt(Instant.now().minusSeconds(10))).await();
            SignedNostrEvent latest = signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d", "article")).await();
            memory.addEvent(old).await();
            memory.addEvent(latest).await();
            memory.references.get(1).clear();
            assertEquals(old.getId(), memory.query(List.of(new NostrFilter().withKind(30023).limit(1))).await().get(0).getId());
            assertEquals(1, memory.size().await().intValue());
        } finally { signer.close(); }
    }

    @Test
    public void customFiltersAreEvaluatedAgainstTheEvent() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent event = note(signer, "Custom predicate", Instant.now().minusSeconds(1));
            store.addEvent(event).await();
            NostrFilter custom = new NostrFilter() {
                @Override
                public boolean matches(SignedNostrEvent candidate, int count) {
                    return count == 0 && "Custom predicate".equals(candidate.getContent());
                }
            }.withKind(99).limit(1);
            assertEquals(event.getId(), store.query(List.of(custom)).await().get(0).getId());
        } finally { signer.close(); }
    }
}
