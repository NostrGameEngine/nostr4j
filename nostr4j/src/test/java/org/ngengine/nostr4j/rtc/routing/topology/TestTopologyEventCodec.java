/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestTopologyEventCodec {

    @Test
    public void testPrivateTopologyRoundTripAndPublicTagPrivacy() {
        Fixture fixture = new Fixture();
        SignedNostrEvent event = NGEUtils.awaitNoThrow(
            fixture.codec.encode(fixture.snapshot, fixture.local, fixture.roomKeys, fixture.issuedAt)
        );

        assertEquals(30350, event.getKind());
        assertEquals("dc-topology", event.getFirstTagFirstValue("t"));
        assertEquals("dc4", event.getFirstTagFirstValue("version"));
        assertNull(event.getFirstTag("neighbor"));
        assertNull(event.getFirstTag("edge"));
        assertNull(event.getFirstTag("transport"));
        assertNull(event.getFirstTag("routingPublicKey"));
        assertFalse(event.getContent().contains(fixture.neighbor.getSessionId()));
        assertFalse(event.getContent().contains(fixture.neighborNode.asHex()));
        assertFalse(event.getContent().contains(fixture.edgeId.asHex()));

        TopologySnapshot decoded = NGEUtils.awaitNoThrow(
            fixture.codec.decode(event, fixture.scope, fixture.localPresence, fixture.roomKeys, fixture.issuedAt)
        );
        assertEquals(fixture.snapshot.getNodeId(), decoded.getNodeId());
        assertEquals(fixture.snapshot.getRoutingPublicKey(), decoded.getRoutingPublicKey());
        assertEquals(1L, decoded.getRevision());
        assertEquals(1, decoded.getNeighbors().size());
        assertEquals(fixture.neighborNode, decoded.getNeighbors().get(0).getNodeId());
        assertEquals(fixture.edgeId, decoded.getNeighbors().get(0).getEdgeId());
    }

    @Test
    public void testOutsiderCannotDecryptAndInvalidRoomproofIsRejected() {
        Fixture fixture = new Fixture();
        SignedNostrEvent event = NGEUtils.awaitNoThrow(
            fixture.codec.encode(fixture.snapshot, fixture.local, fixture.roomKeys, fixture.issuedAt)
        );

        expectFailure(() ->
            NGEUtils.awaitNoThrow(
                fixture.codec.decode(event, fixture.scope, fixture.localPresence, new NostrKeyPair(), fixture.issuedAt)
            )
        );

        UnsignedNostrEvent tampered = new UnsignedNostrEvent(new HashMap<String, Object>(event.toMap()));
        tampered.replaceTag("roomproof", "00", "00");
        SignedNostrEvent resigned = NGEUtils.awaitNoThrow(fixture.local.getSigner().sign(tampered));
        expectFailure(() ->
            NGEUtils.awaitNoThrow(
                fixture.codec.decode(resigned, fixture.scope, fixture.localPresence, fixture.roomKeys, fixture.issuedAt)
            )
        );
    }

    @Test
    public void testWrongScopeAndExpiredSnapshotAreRejected() {
        Fixture fixture = new Fixture();
        SignedNostrEvent event = NGEUtils.awaitNoThrow(
            fixture.codec.encode(fixture.snapshot, fixture.local, fixture.roomKeys, fixture.issuedAt)
        );
        RoutingScope wrongScope = new RoutingScope(fixture.roomKeys.getPublicKey(), "wrong-protocol", "topology-app");
        expectFailure(() ->
            NGEUtils.awaitNoThrow(
                fixture.codec.decode(event, wrongScope, fixture.localPresence, fixture.roomKeys, fixture.issuedAt)
            )
        );

        Instant oldIssued = fixture.issuedAt.minusSeconds(120);
        TopologySnapshot expired = new TopologySnapshot(
            fixture.scope,
            fixture.local.getPubkey(),
            fixture.local.getSessionId(),
            2L,
            fixture.localNode,
            fixture.routingKeys.getPublicKey(),
            oldIssued,
            oldIssued.plusSeconds(30),
            List.of()
        );
        SignedNostrEvent expiredEvent = NGEUtils.awaitNoThrow(
            fixture.codec.encode(expired, fixture.local, fixture.roomKeys, oldIssued)
        );
        expectFailure(() ->
            NGEUtils.awaitNoThrow(
                fixture.codec.decode(expiredEvent, fixture.scope, fixture.localPresence, fixture.roomKeys, fixture.issuedAt)
            )
        );
    }

    @Test
    public void testNodeIdDistinguishesSessionsSharingPubkey() {
        Fixture fixture = new Fixture();
        NodeId first = NodeId.derive(fixture.scope, fixture.local.getPubkey(), "session-one");
        NodeId second = NodeId.derive(fixture.scope, fixture.local.getPubkey(), "session-two");
        assertFalse(first.equals(second));
        assertEquals(first, NodeId.fromHex(first.asHex()));
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            fail("Expected topology validation failure");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    private static final class Fixture {

        private final NostrKeyPair roomKeys = new NostrKeyPair();
        private final NostrKeyPair routingKeys = new NostrKeyPair();
        private final RoutingScope scope = new RoutingScope(roomKeys.getPublicKey(), "topology-protocol", "topology-app");
        private final NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            List.of(),
            scope.getApplicationId(),
            scope.getProtocolId(),
            "local-session",
            roomKeys,
            null
        );
        private final NostrRTCLocalPeer neighbor = new NostrRTCLocalPeer(
            NostrKeyPairSigner.generate(),
            List.of(),
            scope.getApplicationId(),
            scope.getProtocolId(),
            "neighbor-session",
            roomKeys,
            null
        );
        private final NostrRTCPeer localPresence = new NostrRTCPeer(
            local.getPubkey(),
            scope.getApplicationId(),
            scope.getProtocolId(),
            local.getSessionId(),
            roomKeys.getPublicKey(),
            null
        );
        private final NodeId localNode = NodeId.derive(scope, local.getPubkey(), local.getSessionId());
        private final NodeId neighborNode = NodeId.derive(scope, neighbor.getPubkey(), neighbor.getSessionId());
        private final EdgeId edgeId = EdgeId.derive(scope, localNode, neighborNode);
        private final Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        private final TopologySnapshot snapshot = new TopologySnapshot(
            scope,
            local.getPubkey(),
            local.getSessionId(),
            1L,
            localNode,
            routingKeys.getPublicKey(),
            issuedAt,
            issuedAt.plusSeconds(60),
            List.of(
                new TopologyNeighbor(neighborNode, neighbor.getPubkey(), neighbor.getSessionId(), edgeId, TopologyTransport.RTC)
            )
        );
        private final TopologyEventCodec codec = new TopologyEventCodec();
    }
}
