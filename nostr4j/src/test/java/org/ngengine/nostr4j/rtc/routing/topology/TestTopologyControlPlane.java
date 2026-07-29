/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class TestTopologyControlPlane {

    @Test
    public void testPublisherUsesMonotonicRevisionAndCreatedAt() {
        NostrKeyPair roomKeys = new NostrKeyPair();
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            new NostrKeyPairSigner(new NostrKeyPair()),
            Collections.emptyList(),
            "control-app",
            "control-proto",
            "control-session",
            roomKeys,
            null
        );
        RoutingScope scope = new RoutingScope(roomKeys.getPublicKey(), local.getProtocolId(), local.getApplicationId());
        CapturingPool pool = new CapturingPool();
        TopologyControlPlane control = new TopologyControlPlane(
            scope,
            local,
            roomKeys,
            new NostrKeyPair(),
            pool,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5)
        );
        try {
            Instant now = Instant.now();
            SignedNostrEvent first = NGEUtils.awaitNoThrow(control.publishNow(now));
            SignedNostrEvent second = NGEUtils.awaitNoThrow(control.publishNow(now));
            assertEquals("1", first.getFirstTagFirstValue("revision"));
            assertEquals("2", second.getFirstTagFirstValue("revision"));
            assertTrue(second.getCreatedAt().isAfter(first.getCreatedAt()));
            assertEquals(2, pool.events.size());
            assertEquals(1, control.getSnapshots(now).size());
        } finally {
            control.close();
        }
    }

    @Test
    public void testOldReplacementAndExpiredSnapshotAreIgnored() {
        NostrKeyPair roomKeys = new NostrKeyPair();
        RoutingScope scope = new RoutingScope(roomKeys.getPublicKey(), "store-proto", "store-app");
        NostrKeyPair author = new NostrKeyPair();
        NodeId node = NodeId.derive(scope, author.getPublicKey(), "session");
        Instant now = Instant.now();
        TopologySnapshotStore store = new TopologySnapshotStore(2);

        TopologySnapshot revisionTwo = snapshot(scope, author, node, 2L, now, now.plusSeconds(30));
        TopologySnapshot revisionOne = snapshot(scope, author, node, 1L, now.plusSeconds(1), now.plusSeconds(30));
        TopologySnapshot sameRevision = snapshot(scope, author, node, 2L, now.plusSeconds(2), now.plusSeconds(30));
        assertTrue(store.accept(revisionTwo, now));
        assertFalse(store.accept(revisionOne, now));
        assertFalse(store.accept(sameRevision, now));

        TopologySnapshot expired = snapshot(
            scope,
            new NostrKeyPair(),
            NodeId.derive(scope, new NostrKeyPair().getPublicKey(), "other"),
            1L,
            now.minusSeconds(20),
            now.minusSeconds(1)
        );
        assertFalse(store.accept(expired, now));
        assertEquals(1, store.size());
    }

    private static TopologySnapshot snapshot(
        RoutingScope scope,
        NostrKeyPair author,
        NodeId node,
        long revision,
        Instant issued,
        Instant expires
    ) {
        return new TopologySnapshot(
            scope,
            author.getPublicKey(),
            "session",
            revision,
            node,
            new NostrKeyPair().getPublicKey(),
            issued,
            expires,
            List.of()
        );
    }

    private static final class CapturingPool extends NostrPool {

        private final List<SignedNostrEvent> events = new ArrayList<SignedNostrEvent>();

        @Override
        public List<AsyncTask<NostrMessageAck>> publish(SignedNostrEvent event) {
            events.add(event);
            return Collections.emptyList();
        }
    }
}
