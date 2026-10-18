package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.*;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.listeners.sub.NostrSubAllListener;
import org.ngengine.nostr4j.nip50.NostrSearchFilter;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrWaitForEventFetchPolicy;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.proto.impl.NostrEOSEMessage;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.store.NostrCacheRelay;
import org.ngengine.nostr4j.store.NostrEventCache;
import org.ngengine.nostr4j.store.NostrInMemoryEventStore;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class TestPriorityCacheRelay {
    static class Relay extends NostrRelay {
        final List<NostrSubscription> requests=new CopyOnWriteArrayList<>();
        final AtomicInteger closes=new AtomicInteger(),publications=new AtomicInteger();
        Relay(String url) {super(url,NGEUtils.getPlatform().newAsyncExecutor(Relay.class),true);}
        @Override
        public AsyncTask<NostrRelay> connect() {if(isConnected()) return AsyncTask.completed(this);updateStatus(Status.CONNECTED);for(NostrRelayComponent component:listeners) component.onRelayConnect(this);return AsyncTask.completed(this);}
        @Override
        public AsyncTask<NostrRelay> disconnect(String reason,boolean reconnect) {updateStatus(Status.DISCONNECTED);executor.close();return AsyncTask.completed(this);}
        @Override
        public AsyncTask<NostrMessageAck> sendMessage(NostrMessage message) {
            for(NostrRelayComponent component:listeners) component.onRelaySend(this,message);
            if(message instanceof NostrSubscription) requests.add((NostrSubscription)message);
            if(NostrMessage.toSerial(message).get(0).equals("CLOSE")) closes.incrementAndGet();
            if(message instanceof SignedNostrEvent) publications.incrementAndGet();
            NostrMessageAck ack=NostrMessage.ack(this,"test",Instant.now(),null,null);ack.callSuccessCallback("test");return AsyncTask.completed(ack);
        }
        void emit(NostrMessage message) {for(NostrRelayComponent component:listeners) component.onRelayMessage(this,message);}
        void event(SignedNostrEvent event) {emit(new SignedNostrEvent.ReceivedSignedNostrEvent(requests.get(0).getId(),event.toMap()));}
        void eose() {emit(new NostrEOSEMessage(requests.get(0).getId()));}
    }

    static void waitFor(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    @Test
    public void cacheStreamsFirstButAllEoseIncludesUnstartedNetworkRelays() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10));NostrCacheRelay local=new NostrCacheRelay(cache);Relay remote=new Relay("wss://test.invalid");NostrPool pool=new NostrPool();
        try {
            SignedNostrEvent event=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Cached")).await();cache.put(event).await();
            pool.addRelay(remote).await();pool.addRelay(local).await();CopyOnWriteArrayList<String> seen=new CopyOnWriteArrayList<String>();CopyOnWriteArrayList<Boolean> all=new CopyOnWriteArrayList<Boolean>();
            NostrSubscription sub=pool.subscribe(new NostrFilter().withKind(1));sub.addEventListener((s,e,stored)->seen.add(e.getId()));sub.addEoseListener((s,r,everywhere)->all.add(everywhere));sub.open();
            waitFor(()->!remote.requests.isEmpty());assertEquals(List.of(event.getId()),seen);assertEquals(List.of(false),all);
            remote.event(event);remote.eose();waitFor(()->all.contains(true));assertEquals(1,seen.size());sub.close();assertEquals(1,remote.closes.get());
        } finally {pool.clean();local.close();remote.disconnect("cleanup",false);signer.close();}
    }

    @Test
    public void requestedCountCanFinishInCacheWithoutStartingLowerPriorityTier() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10));NostrCacheRelay local=new NostrCacheRelay(cache);Relay remote=new Relay("wss://test.invalid");NostrPool pool=new NostrPool();
        try {
            SignedNostrEvent event=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Complete by ID")).await();cache.put(event).await();pool.addRelay(remote).await();pool.addRelay(local).await();
            assertEquals(1,pool.fetch(new NostrFilter().withId(event.getId()),1,false,Duration.ofSeconds(2)).await().size());assertTrue(remote.requests.isEmpty());
        } finally {pool.clean();local.close();remote.disconnect("cleanup",false);signer.close();}
    }

    @Test
    public void networkCapturePreservesStoredFlagAndDirectCacheInsertsRemainLive() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10));NostrCacheRelay local=new NostrCacheRelay(cache);Relay remote=new Relay("wss://test.invalid");NostrPool pool=new NostrPool();
        try {
            pool.addRelay(remote).await();pool.addRelay(local).await();assertEquals("Local event cache",local.getInfo().getName());
            CopyOnWriteArrayList<Boolean> seen=new CopyOnWriteArrayList<Boolean>();NostrSubscription sub=pool.subscribe(new NostrFilter().withKind(1));sub.addEventListener((s,e,stored)->seen.add(stored));sub.open();
            waitFor(()->!remote.requests.isEmpty());SignedNostrEvent stored=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Network storage")).await();remote.event(stored);
            waitFor(()->seen.size()==1);assertEquals(List.of(true),seen);assertNotNull(cache.get(stored.getId()).await());
            AtomicInteger searched=new AtomicInteger();NostrSubscription search=pool.subscribe(new NostrSearchFilter().search("relay-specific search"));
            search.addEventListener((s,e,fromStorage)->searched.incrementAndGet());search.open();waitFor(()->remote.requests.size()==2);
            SignedNostrEvent live=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Direct cache insertion")).await();cache.put(live).await();
            waitFor(()->seen.size()==2);assertEquals(List.of(true,false),seen);remote.event(live);sub.close();search.close();assertEquals(2,seen.size());assertEquals(0,searched.get());
        } finally {pool.clean();local.close();remote.disconnect("cleanup",false);signer.close();}
    }

    @Test
    public void firstEosePolicyIsRespectedAndNetworkPublishesNeverUseLocalAcks() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10));NostrCacheRelay local=new NostrCacheRelay(cache);Relay remote=new Relay("wss://test.invalid");NostrPool pool=new NostrPool();
        try {
            pool.addRelay(local).await();pool.addRelay(remote).await();
            AsyncTask<List<SignedNostrEvent>> task=pool.fetch(List.of(new NostrFilter()),(sub,events,end)->new NostrSubAllListener(){
                @Override
                public void onSubEose(NostrSubscription s,NostrRelay relay,boolean all){end.run();}
                @Override
                public void onSubEvent(NostrSubscription s,SignedNostrEvent event,boolean stored){ }
                @Override
                public void onSubOpen(NostrSubscription s){ }
                @Override
                public void onSubClose(NostrSubscription s,List<String> reasons){ }
            });task.await();assertTrue(remote.requests.isEmpty());
            SignedNostrEvent event=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Network ACK")).await();List<AsyncTask<NostrMessageAck>> acknowledgements=pool.publish(event).await();assertEquals(1,acknowledgements.size());assertEquals(remote,acknowledgements.get(0).await().getRelay());
        } finally {pool.clean();local.close();remote.disconnect("cleanup",false);signer.close();}
    }

    @Test
    public void priorityTimeoutFallsBackAndCancellationClosesReqWithoutDisconnectingRelay() throws Exception {
        Relay high=new Relay("wss://high.invalid");high.setQueryPriority(10);Relay low=new Relay("wss://low.invalid");NostrPool pool=new NostrPool();pool.setPriorityTimeout(Duration.ofMillis(30));
        try {
            pool.addRelay(high).await();pool.addRelay(low).await();AsyncTask<List<SignedNostrEvent>> task=pool.fetch(new NostrFilter().withKind(1),10,false,Duration.ofSeconds(2));
            waitFor(()->!low.requests.isEmpty());task.cancel();waitFor(()->high.closes.get()==1 && low.closes.get()==1);assertTrue(high.isConnected());assertTrue(low.isConnected());
        } finally {pool.clean();high.disconnect("cleanup",false);low.disconnect("cleanup",false);}
    }

    @Test
    public void tierDeadlineIsNotAnEoseAndRemovingRelayUnblocksLowerPriority() throws Exception {
        Relay high=new Relay("wss://high.invalid");high.setQueryPriority(10);Relay low=new Relay("wss://low.invalid");NostrPool pool=new NostrPool();pool.setPriorityTimeout(Duration.ofMillis(30));
        try {
            pool.addRelay(high).await();pool.addRelay(low).await();CopyOnWriteArrayList<Boolean> all=new CopyOnWriteArrayList<Boolean>();
            NostrSubscription sub=pool.subscribe(new NostrFilter());sub.addEoseListener((s,r,everywhere)->all.add(everywhere));sub.open();
            waitFor(()->!low.requests.isEmpty());low.eose();waitFor(()->!all.isEmpty());assertEquals(List.of(false),all);
            high.eose();waitFor(()->all.contains(true));sub.close();
            pool.setPriorityTimeout(Duration.ofSeconds(2));high.requests.clear();low.requests.clear();
            NostrSubscription next=pool.subscribe(new NostrFilter());next.open();assertTrue(low.requests.isEmpty());
            assertEquals(high,pool.removeRelay(high));waitFor(()->!low.requests.isEmpty());next.close();
        } finally {pool.clean();high.disconnect("cleanup",false);low.disconnect("cleanup",false);}
    }

    @Test
    public void cacheOnlyPoolCannotAcknowledgeNetworkPublication() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrCacheRelay local=new NostrCacheRelay(new NostrEventCache(new NostrInMemoryEventStore(10)));NostrPool pool=new NostrPool();
        try {
            pool.addRelay(local).await();SignedNostrEvent event=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("No network")).await();
            try {pool.publish(event).await();fail("A local cache is not a network publication");}
            catch(Exception expected){assertTrue(expected.getMessage().contains("No network relays"));}
        } finally {pool.clean();local.close();signer.close();}
    }

    @Test
    public void liveCacheDeliveryDoesNotHoldTheSubscriptionRegistryMonitor() throws Exception {
        NostrKeyPairSigner signer=NostrKeyPairSigner.generate();NostrEventCache cache=new NostrEventCache(new NostrInMemoryEventStore(10));NostrCacheRelay local=new NostrCacheRelay(cache);NostrPool pool=new NostrPool();
        try {
            Field registry=NostrCacheRelay.class.getDeclaredField("active");registry.setAccessible(true);Object lock=registry.get(local);
            pool.addRelay(local).await();CopyOnWriteArrayList<Boolean> received=new CopyOnWriteArrayList<Boolean>();AtomicInteger eose=new AtomicInteger();
            NostrSubscription sub=pool.subscribe(new NostrFilter().withKind(1));
            sub.addEoseListener((s,r,all)->eose.incrementAndGet());
            sub.addEventListener((s,event,stored)->{received.add(Thread.holdsLock(lock));s.close();});
            sub.open();waitFor(()->eose.get()>0);
            SignedNostrEvent event=signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Live cache callback outside registry lock")).await();cache.put(event).await();
            waitFor(()->!received.isEmpty());assertEquals(List.of(false),received);
        } finally {pool.clean();local.close();signer.close();}
    }
    @Test
    public void rejectedCacheInsertionDoesNotDeduplicateAValidLaterDelivery() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        NostrEventCache cache = new NostrEventCache(new NostrInMemoryEventStore(10));
        NostrCacheRelay local = new NostrCacheRelay(cache);
        Relay remote = new Relay("wss://test.invalid");
        NostrPool pool = new NostrPool();
        try {
            pool.addRelay(local).await();
            pool.addRelay(remote).await();
            NostrSubscription sub = pool.subscribe(new NostrFilter().withKind(1));
            CopyOnWriteArrayList<String> delivered = new CopyOnWriteArrayList<>();
            AtomicInteger eose = new AtomicInteger();
            sub.addEventListener((ignored, event, stored) -> delivered.add(event.getId()));
            sub.addEoseListener((ignored, relay, all) -> { if (all) eose.incrementAndGet(); });
            sub.open();
            waitFor(() -> !remote.requests.isEmpty());
            SignedNostrEvent valid = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Valid later delivery")).await();
            LinkedHashMap<String, Object> invalid = new LinkedHashMap<>(valid.toMap());
            invalid.put("sig", "0".repeat(128));
            remote.event(new SignedNostrEvent(invalid));
            remote.event(valid);
            remote.eose();
            waitFor(() -> eose.get() == 1);
            assertEquals(List.of(valid.getId()), delivered);
            sub.close();
        } finally {
            pool.clean();
            local.close();
            remote.disconnect("cleanup", false);
            signer.close();
        }
    }

}
