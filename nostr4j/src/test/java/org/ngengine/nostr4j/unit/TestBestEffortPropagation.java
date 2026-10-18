package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;

public class TestBestEffortPropagation {

    static class Relay extends TestConnectionBudget.Relay {

        final List<SignedNostrEvent> events = new CopyOnWriteArrayList<>();

        Relay(String url) {
            super(url);
        }

        @Override
        public AsyncTask<NostrMessageAck> sendMessage(NostrMessage message) {
            if (message instanceof SignedNostrEvent) events.add((SignedNostrEvent) message);
            return super.sendMessage(message);
        }
    }

    @Test
    public void positiveBudgetEnablesPropagationAndRetainsAckSemantics() throws Exception {
        NostrPool pool = new NostrPool();
        Relay first = new Relay("wss://first.test");
        Relay second = new Relay("wss://second.test");
        Relay third = new Relay("wss://third.test");
        try {
            assertFalse(pool.isBestEffortPropagation());
            pool.setConnectionBudget(1);
            assertTrue(pool.isBestEffortPropagation());
            first.setConnectionPriority(100);
            pool.addRelay(first);
            pool.addRelay(second);
            pool.addRelay(third);
            TestConnectionBudget.until(first::isConnected);
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("public")).await();
            pool.setBestEffortPropagationInterval(Duration.ofMillis(40));
            assertEquals(1, pool.publish(event).await().size());
            TestConnectionBudget.until(() ->
                pool.getPendingBestEffortPropagations() == 0 && second.events.size() == 1 && third.events.size() == 1
            );
            TestConnectionBudget.until(() -> first.isConnected() && !second.isConnected() && !third.isConnected());
        } finally {
            pool.clean();
            first.finish();
            second.finish();
            third.finish();
        }
    }

    @Test
    public void expirationRemovalDisablingAndSensitiveEventsNeverLeakToStandby() throws Exception {
        NostrPool pool = new NostrPool();
        Relay first = new Relay("wss://first.test");
        Relay second = new Relay("wss://second.test");
        try {
            pool.setConnectionBudget(1);
            first.setConnectionPriority(100);
            pool.addRelay(first);
            pool.addRelay(second);
            TestConnectionBudget.until(first::isConnected);
            pool.setBestEffortPropagation(true);
            pool.setBestEffortPropagationInterval(Duration.ofMillis(250));
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            for (int kind : new int[] { 4, 13, 14, 1059, 22242, 23194, 24133, 24242 }) {
                pool
                    .publish(signer.sign(new UnsignedNostrEvent().withKind(kind).withContent("private/control")).await())
                    .await();
                assertEquals(0, pool.getPendingBestEffortPropagations());
            }
            pool
                .publish(
                    signer.sign(new UnsignedNostrEvent().withKind(1).withExpiration(Instant.now().minusSeconds(1))).await()
                )
                .await();
            assertEquals(0, pool.getPendingBestEffortPropagations());
            SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("queued")).await();
            pool.publish(event).await();
            assertEquals(1, pool.getPendingBestEffortPropagations());
            pool.removeRelay(second);
            TestConnectionBudget.until(() -> pool.getPendingBestEffortPropagations() == 0);
            assertTrue(second.events.isEmpty());
            pool.addRelay(second);
            pool.publish(event).await();
            pool.setBestEffortPropagation(false);
            assertEquals(0, pool.getPendingBestEffortPropagations());
            Thread.sleep(350);
            assertTrue(second.events.isEmpty());
        } finally {
            pool.clean();
            first.finish();
            second.finish();
        }
    }

    @Test
    public void explicitOptOutSurvivesBudgetChanges() {
        NostrPool pool = new NostrPool();
        try {
            assertFalse(pool.isBestEffortPropagation());
            pool.setBestEffortPropagation(true);
            assertFalse(pool.isBestEffortPropagation());
            pool.setBestEffortPropagation(false);
            pool.setConnectionBudget(1);
            assertFalse(pool.isBestEffortPropagation());
            pool.setConnectionBudget(2);
            assertFalse(pool.isBestEffortPropagation());
            pool.setBestEffortPropagation(true);
            assertTrue(pool.isBestEffortPropagation());
            pool.setConnectionBudget(0);
            assertFalse(pool.isBestEffortPropagation());
            pool.setConnectionBudget(1);
            assertTrue(pool.isBestEffortPropagation());
            assertThrows(IllegalArgumentException.class, () -> pool.setConnectionBudget(-1));
            assertTrue(pool.isBestEffortPropagation());
        } finally {
            pool.clean();
        }
    }

    @Test
    public void removingTheBudgetClearsPendingPropagation() throws Exception {
        NostrPool pool = new NostrPool();
        Relay first = new Relay("wss://first.test");
        Relay second = new Relay("wss://second.test");
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            pool.setBestEffortPropagationInterval(Duration.ofMillis(250));
            pool.setConnectionBudget(1);
            first.setConnectionPriority(100);
            pool.addRelay(first);
            pool.addRelay(second);
            TestConnectionBudget.until(first::isConnected);
            SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Pending propagation")).await();
            pool.publish(event).await();
            assertEquals(1, pool.getPendingBestEffortPropagations());
            pool.setConnectionBudget(0);
            assertFalse(pool.isBestEffortPropagation());
            assertEquals(0, pool.getPendingBestEffortPropagations());
            Thread.sleep(350);
            assertTrue(second.events.isEmpty());
        } finally {
            pool.clean();
            first.finish();
            second.finish();
            signer.close();
        }
    }

    @Test
    public void renamedSettingsKeepValidationAndLimitTheQueue() throws Exception {
        NostrPool pool = new NostrPool();
        Relay first = new Relay("wss://first.test");
        Relay second = new Relay("wss://second.test");
        Relay third = new Relay("wss://third.test");
        Relay fourth = new Relay("wss://fourth.test");
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            assertThrows(IllegalArgumentException.class, () -> pool.setBestEffortPropagationQueueCapacity(0));
            assertThrows(IllegalArgumentException.class, () -> pool.setBestEffortPropagationQueueCapacity(100_001));
            assertThrows(IllegalArgumentException.class, () -> pool.setBestEffortPropagationInterval(null));
            assertThrows(IllegalArgumentException.class, () -> pool.setBestEffortPropagationInterval(Duration.ofMillis(9)));
            assertThrows(IllegalArgumentException.class, () -> pool.setBestEffortPropagationInterval(Duration.ofMillis(60_001)));
            pool.setBestEffortPropagationInterval(Duration.ofMillis(10));
            pool.setBestEffortPropagationInterval(Duration.ofMinutes(1));
            pool.setBestEffortPropagationQueueCapacity(2);
            pool.setConnectionBudget(1);
            first.setConnectionPriority(100);
            pool.addRelay(first);
            pool.addRelay(second);
            pool.addRelay(third);
            pool.addRelay(fourth);
            TestConnectionBudget.until(first::isConnected);
            SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("Bounded propagation")).await();
            assertEquals(1, pool.publish(event).await().size());
            assertEquals(2, pool.getPendingBestEffortPropagations());
            pool.setBestEffortPropagationQueueCapacity(1);
            assertEquals(1, pool.getPendingBestEffortPropagations());
        } finally {
            pool.clean();
            first.finish();
            second.finish();
            third.finish();
            fourth.finish();
            signer.close();
        }
    }
}
