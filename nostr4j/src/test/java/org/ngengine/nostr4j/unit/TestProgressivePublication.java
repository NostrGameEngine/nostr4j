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

public class TestProgressivePublication {

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
    public void defaultIsOffAndOptInRetainsAckSemanticsAndConnectionBudget() throws Exception {
        NostrPool pool = new NostrPool();
        Relay first = new Relay("wss://first.test");
        Relay second = new Relay("wss://second.test");
        Relay third = new Relay("wss://third.test");
        try {
            assertFalse(pool.isProgressivePublication());
            pool.setConnectionBudget(1);
            first.setConnectionPriority(100);
            pool.addRelay(first);
            pool.addRelay(second);
            pool.addRelay(third);
            TestConnectionBudget.until(first::isConnected);
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("public")).await();
            pool.publish(event).await();
            assertEquals(0, pool.getPendingPublications());
            assertTrue(second.events.isEmpty());
            pool.setPublicationInterval(Duration.ofMillis(40));
            pool.setProgressivePublication(true);
            assertEquals(1, pool.publish(event).await().size());
            TestConnectionBudget.until(() ->
                pool.getPendingPublications() == 0 && second.events.size() == 1 && third.events.size() == 1
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
            pool.setProgressivePublication(true);
            pool.setPublicationInterval(Duration.ofMillis(250));
            NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
            for (int kind : new int[] { 4, 13, 14, 1059, 22242, 23194, 24133, 24242 }) {
                pool
                    .publish(signer.sign(new UnsignedNostrEvent().withKind(kind).withContent("private/control")).await())
                    .await();
                assertEquals(0, pool.getPendingPublications());
            }
            pool
                .publish(
                    signer.sign(new UnsignedNostrEvent().withKind(1).withExpiration(Instant.now().minusSeconds(1))).await()
                )
                .await();
            assertEquals(0, pool.getPendingPublications());
            SignedNostrEvent event = signer.sign(new UnsignedNostrEvent().withKind(1).withContent("queued")).await();
            pool.publish(event).await();
            assertEquals(1, pool.getPendingPublications());
            pool.removeRelay(second);
            TestConnectionBudget.until(() -> pool.getPendingPublications() == 0);
            assertTrue(second.events.isEmpty());
            pool.addRelay(second);
            pool.publish(event).await();
            pool.setProgressivePublication(false);
            assertEquals(0, pool.getPendingPublications());
            Thread.sleep(350);
            assertTrue(second.events.isEmpty());
        } finally {
            pool.clean();
            first.finish();
            second.finish();
        }
    }
}
