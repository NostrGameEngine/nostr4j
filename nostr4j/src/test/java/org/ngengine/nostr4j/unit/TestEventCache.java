package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.store.NostrEventCache;
import org.ngengine.nostr4j.store.NostrInMemoryEventStore;

public class TestEventCache {
    @Test
    public void regularEventsAreCacheFirstAndGlobalTtlCannotBeExtendedByDuplicates() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();AtomicReference<Instant> now=new AtomicReference<>(Instant.now());NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(2), 8, now::get);
        try {
            SignedNostrEvent event=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Persistent")).await();
            cache.put(event.getId(),event).await();now.set(now.get().plusSeconds(60));assertSame(event,cache.get(event.getId()).await());
            cache.setTtl(Duration.ofSeconds(120)).await();cache.put(event.getId(),event).await();now.set(now.get().plusSeconds(61));assertNull(cache.get(event.getId()).await());
            assertEquals(0,cache.size().await().intValue());
        } finally {signer.close();}
    }

    @Test
    public void replaceableAndEphemeralPoliciesAndExpirationAreIndependent() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();AtomicReference<Instant> now=new AtomicReference<>(Instant.now());NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10), 40, now::get);
        try {
            SignedNostrEvent profile=signer.sign(new UnsignedNostrEvent().withKind(0).withContent("{}")).await();cache.put(profile.getId(),profile).await();
            now.set(now.get().plusMillis(101));assertNull(cache.get(profile.getId()).await());
            SignedNostrEvent temporary=signer.sign(new UnsignedNostrEvent().withKind(21000).withContent("Ephemeral")).await();cache.put(temporary.getId(),temporary).await();assertNull(cache.get(temporary.getId()).await());
            cache.setEphemeralTtl(Duration.ofMillis(200)).await();cache.put(temporary.getId(),temporary).await();assertNotNull(cache.get(temporary.getId()).await());
            now.set(now.get().plusMillis(201));assertNull(cache.get(temporary.getId()).await());
            SignedNostrEvent expiring=signer.sign(new UnsignedNostrEvent().withKind(1).withExpiration(now.get().plusSeconds(1)).withContent("Expires")).await();cache.put(expiring.getId(),expiring).await();
            now.set(expiring.getExpiration());assertNull(cache.get(expiring.getId()).await());
        } finally {signer.close();}
    }

    @Test
    public void queriesHonorFiltersLatestVersionsLimitsAndLruCapacity() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(3));cache.setReplaceableTtl(null).await();
        try {
            SignedNostrEvent old=signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d","article").createdAt(Instant.now().minusSeconds(5)).withContent("Old")).await();
            SignedNostrEvent latest=signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d","article").withContent("Latest")).await();
            SignedNostrEvent note=signer.sign(new UnsignedNostrEvent().withKind(1).withTag("t","test").withContent("Note")).await();
            for(SignedNostrEvent event:List.of(old,latest,note)) cache.put(event.getId(),event).await();
            assertEquals(List.of(latest),cache.query(List.of(new NostrFilter().withKind(30023))).await());
            assertEquals(List.of(old),cache.query(List.of(new NostrFilter().withId(old.getId()))).await());
            assertEquals(List.of(note),cache.query(List.of(new NostrFilter().withTag("t","test").limit(1))).await());
            assertTrue(cache.query(List.of(new NostrFilter().limit(0))).await().isEmpty());
            cache.get(old.getId()).await();SignedNostrEvent added=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Fourth")).await();cache.put(added.getId(),added).await();
            assertNull(cache.get(latest.getId()).await());assertNotNull(cache.get(old.getId()).await());cache.invalidate(old.getId()).await();assertNull(cache.get(old.getId()).await());cache.clear().await();assertEquals(0,cache.size().await().intValue());
        } finally {signer.close();}
    }

    @Test
    public void deletionAuthorAndAddressTimestampAreValidatedAndTombstonesSurviveClear() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrKeyPairSigner attacker=NostrKeyPairSigner.generate();NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10));cache.setHonorDeletions(true).await();cache.setReplaceableTtl(null).await();
        try {
            SignedNostrEvent note=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Author owned")).await();cache.put(note.getId(),note).await();
            SignedNostrEvent forged=attacker.sign(new UnsignedNostrEvent().withKind(5).withTag("e",note.getId())).await();cache.put(forged.getId(),forged).await();assertNotNull(cache.get(note.getId()).await());
            SignedNostrEvent deletion=signer.sign(new UnsignedNostrEvent().withKind(5).withTag("e",note.getId())).await();cache.put(deletion.getId(),deletion).await();assertNull(cache.get(note.getId()).await());
            cache.clear().await();cache.put(note.getId(),note).await();assertNull(cache.get(note.getId()).await());
            SignedNostrEvent old=signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d","article").createdAt(Instant.now().minusSeconds(10))).await();
            String address="30023:"+signer.getPublicKey().await().asHex()+":article";
            SignedNostrEvent deleteAddress=signer.sign(new UnsignedNostrEvent().withKind(5).withTag("a",address).createdAt(Instant.now().minusSeconds(5))).await();cache.put(deleteAddress.getId(),deleteAddress).await();
            cache.put(old.getId(),old).await();assertNull(cache.get(old.getId()).await());
            SignedNostrEvent newer=signer.sign(new UnsignedNostrEvent().withKind(30023).withTag("d","article").withContent("New version")).await();cache.put(newer.getId(),newer).await();assertNotNull(cache.get(newer.getId()).await());
        } finally {signer.close();attacker.close();}
    }

    @Test
    public void invalidSignaturesNeverEnterCache() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10));
        try {
            SignedNostrEvent event=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Signed")).await();LinkedHashMap<String, Object> map=new LinkedHashMap<>(event.toMap());map.put("content","Tampered");
            SignedNostrEvent invalid=new SignedNostrEvent(map);
            assertThrows(IllegalArgumentException.class,()->cache.put(invalid.getId(),invalid).await());assertEquals(0,cache.size().await().intValue());
        } finally {signer.close();}
    }

    @Test
    public void explicitIdQueriesDoNotScanUnrelatedPayloads() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();AtomicInteger clockReads=new AtomicInteger();
        NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10), 40, ()->{clockReads.incrementAndGet();return Instant.now();});
        try {
            SignedNostrEvent wanted=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Wanted")).await();cache.put(wanted.getId(),wanted).await();
            for(int i=0;i<4;i++) {SignedNostrEvent other=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Other "+i)).await();cache.put(other.getId(),other).await();}
            clockReads.set(0);assertEquals(List.of(wanted),cache.query(List.of(new NostrFilter().withId(wanted.getId()))).await());assertEquals(1,clockReads.get());
            clockReads.set(0);assertTrue(cache.query(List.of(new NostrFilter().withId("0".repeat(64)))).await().isEmpty());assertEquals(0,clockReads.get());
        } finally {signer.close();}
    }
    @Test
    public void slowQueryFiltersDoNotBlockCacheInsertions() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrEventCache cache = new NostrEventCache(new NostrInMemoryEventStore(10));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch filtering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            SignedNostrEvent first = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Snapshot event")).await();
            SignedNostrEvent later = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Concurrent insertion")).await();
            cache.put(first.getId(), first).await();
            NostrFilter slow = new NostrFilter() {
                @Override
                public boolean matches(SignedNostrEvent event, int count) {
                    filtering.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Filter was not released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return super.matches(event, count);
                }
            };
            Future<List<SignedNostrEvent>> query = workers.submit(() -> cache.query(List.of(slow)).await());
            assertTrue(filtering.await(2, TimeUnit.SECONDS));
            Future<SignedNostrEvent> insertion = workers.submit(() -> {
                cache.put(later.getId(), later).await();
                return cache.get(later.getId()).await();
            });
            assertSame("Insertion must finish while the filter is still waiting", later, insertion.get(2, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(List.of(first), query.get(2, TimeUnit.SECONDS));
            assertEquals(2, cache.size().await().intValue());
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            signer.close();
        }
    }

}
