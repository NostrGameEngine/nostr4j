package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.*;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrWaitForEventFetchPolicy;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.store.NostrCacheRelay;
import org.ngengine.nostr4j.store.NostrEventCache;
import org.ngengine.nostr4j.store.NostrInMemoryEventStore;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class TestConnectionBudget {

    @Test
    public void hostConnectionLeasesShareOwnershipWithoutArtificialSubscriptions() throws Exception {
        NostrPool pool = new NostrPool();
        Relay app = new Relay("wss://app.test");
        Relay pin = new Relay("wss://pin.test");
        try {
            pool.setConnectionBudget(1);
            pin.setConnectionPriority(1000);
            pool.registerRelay(pin);
            pool.registerRelay(app);
            AutoCloseable first = pool.retainConnections(List.of(app.getUrl())), second = pool.retainConnections(
                List.of(app.getUrl())
            );
            until(() -> app.isConnected() && !pin.isConnected());
            assertTrue(app.requests.isEmpty());
            first.close();
            first.close();
            assertTrue(app.isConnected());
            NostrSubscription subscription = pool.subscribe(new NostrFilter().withKind(1), List.of(app.getUrl()));
            subscription.open();
            until(() -> !app.requests.isEmpty());
            second.close();
            assertTrue(app.isConnected());
            subscription.close();
            until(() -> pin.isConnected() && !app.isConnected());
        } finally {
            pool.clean();
            app.finish();
            pin.finish();
        }
    }

    static class Relay extends NostrRelay {

        final List<NostrSubscription> requests = new CopyOnWriteArrayList<>();
        int closes;

        Relay(String url) {
            super(url, NGEUtils.getPlatform().newAsyncExecutor(Relay.class), true);
        }

        @Override
        public AsyncTask<NostrRelay> connect() {
            if (isConnected()) return AsyncTask.completed(this);
            for (NostrRelayComponent component : listeners) if (!component.onRelayConnectRequest(this)) return AsyncTask.failed(
                new IllegalStateException("Standby relay")
            );
            updateStatus(Status.CONNECTED);
            for (NostrRelayComponent component : listeners) component.onRelayConnect(this);
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<NostrRelay> disconnect(String reason, boolean reconnect) {
            updateStatus(Status.DISCONNECTED);
            for (NostrRelayComponent component : listeners) component.onRelayDisconnect(this, reason, true);
            return AsyncTask.completed(this);
        }

        @Override
        public AsyncTask<NostrMessageAck> sendMessage(NostrMessage message) {
            for (NostrRelayComponent component : listeners) component.onRelaySend(this, message);
            if (message instanceof NostrSubscription) requests.add((NostrSubscription) message);
            if (NostrMessage.toSerial(message).get(0).equals("CLOSE")) closes++;
            NostrMessageAck ack = NostrMessage.ack(this, "test", Instant.now(), null, null);
            ack.callSuccessCallback("test");
            return AsyncTask.completed(ack);
        }

        void finish() {
            executor.close();
        }
    }

    static void until(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Asynchronous connection state did not converge", ready.getAsBoolean());
    }

    @Test
    public void unlimitedDefaultPreservesLegacyConnectionsAndZeroPriority() throws Exception {
        NostrPool pool = new NostrPool();
        Relay a = new Relay("wss://a.test");
        Relay b = new Relay("wss://b.test");
        try {
            assertEquals(0, pool.getConnectionBudget());
            assertEquals(0, a.getConnectionPriority());
            pool.addRelay(a).await();
            pool.addRelay(b).await();
            assertTrue(a.isConnected() && b.isConnected());
        } finally {
            pool.clean();
            a.finish();
            b.finish();
        }
    }

    @Test
    public void automaticConnectionsFollowPriorityWithoutDeletingCandidates() throws Exception {
        NostrPool pool = new NostrPool();
        Relay a = new Relay("wss://a.test");
        Relay b = new Relay("wss://b.test");
        try {
            pool.setConnectionBudget(1);
            a.setConnectionPriority(10);
            pool.addRelay(a);
            pool.addRelay(b);
            until(() -> a.isConnected() && !b.isConnected());
            b.setConnectionPriority(20);
            until(() -> b.isConnected() && !a.isConnected());
            assertEquals(2, pool.getRelays().size());
            pool.setConnectionBudget(0);
            until(() -> a.isConnected() && b.isConnected());
            a.disconnect("Manually disconnected", false).await();
            Thread.sleep(1200);
            assertFalse("Disabled budget maintenance must not reconnect manually controlled relays", a.isConnected());
        } finally {
            pool.clean();
            a.finish();
            b.finish();
        }
    }

    @Test
    public void hintedConnectionsCanExceedBudgetAndRemainUntilTheirLastOwnerCloses() throws Exception {
        NostrPool pool = new NostrPool();
        Relay a = new Relay("wss://a.test");
        Relay b = new Relay("wss://b.test");
        Relay c = new Relay("wss://c.test");
        try {
            pool.setConnectionBudget(1);
            a.setConnectionPriority(100);
            pool.addRelay(a);
            pool.addRelay(b);
            pool.addRelay(c);
            until(a::isConnected);
            NostrSubscription first = pool.subscribe(new NostrFilter().withKind(1), List.of(b.getUrl()));
            first.open();
            NostrSubscription second = pool.subscribe(new NostrFilter().withKind(1), List.of(b.getUrl(), c.getUrl()));
            second.open();
            until(() -> b.isConnected() && c.isConnected() && !a.isConnected());
            a.setConnectionPriority(1000);
            Thread.sleep(30);
            assertTrue(b.isConnected() && c.isConnected());
            first.close();
            Thread.sleep(30);
            assertTrue(b.isConnected());
            second.close();
            until(() -> a.isConnected() && !b.isConnected() && !c.isConnected());
        } finally {
            pool.clean();
            a.finish();
            b.finish();
            c.finish();
        }
    }

    @Test
    public void scopedReadsDoNotLeakToOtherNewlyConnectedRelays() throws Exception {
        NostrPool pool = new NostrPool();
        Relay a = new Relay("wss://a.test");
        Relay b = new Relay("wss://b.test");
        try {
            pool.setConnectionBudget(1);
            pool.addRelay(a);
            pool.addRelay(b);
            NostrSubscription sub = pool.subscribe(new NostrFilter().withKind(30078), List.of(a.getUrl()));
            sub.open();
            until(() -> !a.requests.isEmpty());
            NostrSubscription other = pool.subscribe(new NostrFilter().withKind(1), List.of(b.getUrl()));
            other.open();
            until(b::isConnected);
            assertTrue(b.requests.stream().noneMatch(request -> request.getId().equals(sub.getId())));
            assertTrue(a.isConnected());
            sub.close();
            other.close();
        } finally {
            pool.clean();
            a.finish();
            b.finish();
        }
    }

    @Test
    public void cancelledFetchReleasesHintsAndVirtualCacheDoesNotConsumeBudget() throws Exception {
        NostrPool pool = new NostrPool();
        Relay a = new Relay("wss://a.test");
        Relay b = new Relay("wss://b.test");
        NostrCacheRelay local = new NostrCacheRelay(new NostrEventCache(new NostrInMemoryEventStore(10)));
        try {
            pool.setConnectionBudget(1);
            a.setConnectionPriority(100);
            pool.addRelay(a);
            pool.addRelay(b);
            pool.addRelay(local);
            until(a::isConnected);
            AsyncTask<List<SignedNostrEvent>> read = pool.fetch(
                List.of(new NostrFilter().withKind(1)),
                NostrWaitForEventFetchPolicy.get(e -> true, 1, false, Duration.ofSeconds(3)),
                List.of(b.getUrl())
            );
            until(() -> b.isConnected() && !a.isConnected());
            assertTrue(local.isConnected());
            read.cancel();
            until(() -> a.isConnected() && !b.isConnected());
            assertTrue(local.isConnected());
        } finally {
            pool.clean();
            local.close();
            a.finish();
            b.finish();
        }
    }

    @Test
    public void connectionPriorityDoesNotSerializeOrdinaryQueries() throws Exception {
        NostrPool pool = new NostrPool();
        Relay a = new Relay("wss://a.test");
        Relay b = new Relay("wss://b.test");
        try {
            pool.setConnectionBudget(2);
            a.setConnectionPriority(1000);
            pool.addRelay(a);
            pool.addRelay(b);
            until(() -> a.isConnected() && b.isConnected());
            NostrSubscription sub = pool.subscribe(new NostrFilter().withKind(1));
            sub.open();
            until(() -> !a.requests.isEmpty() && !b.requests.isEmpty());
            sub.close();
        } finally {
            pool.clean();
            a.finish();
            b.finish();
        }
    }
}
