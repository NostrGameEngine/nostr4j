/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;

public class TestTopologyCompactAddress {

    private static final String APP = "d10cf22b8dc3383fb777b4456407f6ca2a31c01f1d3d0f4ddef21615450d0ad1";
    private static final String SESSION_A = "nostr4j1792581127639-1-" + "1".repeat(32);
    private static final String SESSION_B = "nostr4j1792581127909-2-" + "2".repeat(32);

    @Test(timeout = 12000)
    public void actualTdbTwoRoomAddressesSurviveTheStrfry255ByteReplacementIndex() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
        try {
            Fixture a = new Fixture(signer, APP, APP + ":1", SESSION_A);
            Fixture b = new Fixture(signer, APP, APP + ":1", SESSION_B);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            SignedNostrEvent first = a.event(now, 1);
            SignedNostrEvent second = b.event(now.plusSeconds(1), 1);
            assertTrue(first.verify());
            assertTrue(second.verify());
            assertEquals(264, rawAddress(a.scope, SESSION_A).getBytes(StandardCharsets.UTF_8).length);
            assertEquals(264, rawAddress(b.scope, SESSION_B).getBytes(StandardCharsets.UTF_8).length);
            a.codec.decode(first, a.scope, a.presence(), a.room, now.plusSeconds(2)).await();
            b.codec.decode(second, b.scope, b.presence(), b.room, now.plusSeconds(2)).await();
            Strfry255Index relay = new Strfry255Index();
            assertTrue(relay.accept(second));
            boolean firstAccepted = relay.accept(first);
            System.out.println(
                "TOPOLOGY_255 legacyBytes=264 firstAccepted=" +
                firstAccepted +
                " retained=" +
                relay.latest.size() +
                " rejected=" +
                relay.rejected
            );
            assertTrue("different rooms must not replace each other after real signing and relay indexing", firstAccepted);
            assertEquals(2, relay.latest.size());
        } finally {
            signer.close().await();
        }
    }

    @Test(timeout = 12000)
    public void sameTimestampCrossRoomPublicationsIgnoreLexicalIdAndArrivalOrder() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
        try {
            Fixture a = new Fixture(signer, APP, APP + ":1", SESSION_A), b = new Fixture(signer, APP, APP + ":1", SESSION_B);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            SignedNostrEvent x = a.event(now, 1), y = b.event(now, 1);
            assertTrue(x.verify());
            assertTrue(y.verify());
            assertNotEquals(x.getId(), y.getId());
            for (List<SignedNostrEvent> order : List.of(List.of(x, y), List.of(y, x))) {
                Strfry255Index relay = new Strfry255Index();
                for (SignedNostrEvent event : order) assertTrue(relay.accept(event));
                assertEquals(2, relay.latest.size());
                assertEquals(0, relay.rejected);
            }
        } finally {
            signer.close().await();
        }
    }

    @Test
    public void compactAddressMatchesThePublishedWireVectorEvenForShortLegacyAddresses() {
        RoutingScope scope = new RoutingScope(
            org.ngengine.nostr4j.keypair.NostrPublicKey.fromHex(
                "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
            ),
            "proto",
            "app"
        );
        assertEquals(
            "dc-topology:ea6cf599766bf2470292a068de63386c3ef50fd06efe4e77e02cad9ce6b02f14",
            scope.topologyAddress("session")
        );
        assertNotEquals(scope.legacyTopologyAddress("session"), scope.topologyAddress("session"));
    }

    @Test
    public void compactAddressIsAlwaysUsedAndLegacyPublicationStopsAfter255Utf8Bytes() throws Exception {
        RoutingScope scope = new RoutingScope(new NostrKeyPair().getPublicKey(), "proto", "app");
        int overhead = rawAddress(scope, "").getBytes(StandardCharsets.UTF_8).length;
        String fits = "s".repeat(255 - overhead), longer = fits + "s";
        assertEquals(255, rawAddress(scope, fits).getBytes(StandardCharsets.UTF_8).length);
        assertEquals(expectedAddress(scope, fits, "nip-dc-topology-address-v1"), scope.topologyAddress(fits));
        assertTrue(scope.canPublishLegacyTopology(fits));
        assertFalse(scope.canPublishLegacyTopology(longer));
        assertEquals(256, rawAddress(scope, longer).getBytes(StandardCharsets.UTF_8).length);
        assertEquals(expectedAddress(scope, longer, "nip-dc-topology-address-v1"), scope.topologyAddress(longer));
        assertEquals(76, scope.topologyAddress(longer).getBytes(StandardCharsets.UTF_8).length);
        assertThrows(NullPointerException.class, () -> scope.topologyAddress(null));
    }

    @Test
    public void multibyteSessionUsesByteLengthAndTheIndependentDomainSeparatedHash() throws Exception {
        RoutingScope scope = new RoutingScope(new NostrKeyPair().getPublicKey(), "protocol", "application");
        String session = "é".repeat(100);
        assertTrue(rawAddress(scope, session).length() < 255);
        assertTrue(rawAddress(scope, session).getBytes(StandardCharsets.UTF_8).length > 255);
        String expected = expectedAddress(scope, session, "nip-dc-topology-address-v1");
        assertEquals(expected, scope.topologyAddress(session));
        assertEquals(76, expected.length());
        assertNotEquals(expectedAddress(scope, session, "nip-dc-routing-node-v1"), expected);
        assertEquals(expected, scope.topologyAddress(session));
    }

    @Test
    public void compactHashBindsEveryScopeFieldAndLengthPrefixesAmbiguousColonText() throws Exception {
        NostrKeyPair room = new NostrKeyPair();
        String session = "s".repeat(200);
        RoutingScope a = new RoutingScope(room.getPublicKey(), "p:q", "r"), b = new RoutingScope(
            room.getPublicKey(),
            "p",
            "q:r"
        );
        assertEquals(rawAddress(a, session), rawAddress(b, session));
        assertNotEquals(a.topologyAddress(session), b.topologyAddress(session));
        assertNotEquals(a.topologyAddress(session), a.topologyAddress(session + "s"));
        assertNotEquals(
            a.topologyAddress(session),
            new RoutingScope(new NostrKeyPair().getPublicKey(), "p:q", "r").topologyAddress(session)
        );
        assertNotEquals(
            a.topologyAddress(session),
            new RoutingScope(room.getPublicKey(), "p:q!", "r").topologyAddress(session)
        );
        assertNotEquals(
            a.topologyAddress(session),
            new RoutingScope(room.getPublicKey(), "p:q", "r!").topologyAddress(session)
        );
        assertEquals(expectedAddress(a, session, "nip-dc-topology-address-v1"), a.topologyAddress(session));
    }

    @Test(timeout = 12000)
    public void signedLegacyAliasDoesNotBypassSignatureScopeSessionOrRoomProof() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
        try {
            Fixture f = new Fixture(signer, APP, APP + ":1", SESSION_A);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            SignedNostrEvent compact = f.event(now, 1), legacy = resign(f, compact, "d", rawAddress(f.scope, SESSION_A));
            assertEquals(1L, f.codec.decode(compact, f.scope, f.presence(), f.room, now).await().getRevision());
            assertEquals(1L, f.codec.decode(legacy, f.scope, f.presence(), f.room, now).await().getRevision());
            for (SignedNostrEvent invalid : List.of(
                resign(f, legacy, "d", rawAddress(f.scope, SESSION_B)),
                f.signer.sign(new UnsignedNostrEvent(compact.toMap()).withTag("d", rawAddress(f.scope, SESSION_A))).await(),
                resign(f, compact, "d", compact.getFirstTagFirstValue("d"), "extra-address-value"),
                resign(f, compact, "d", compact.getFirstTagFirstValue("d") + "0"),
                resign(f, legacy, "P", new NostrKeyPair().getPublicKey().asHex()),
                resign(f, legacy, "i", "wrong-protocol"),
                resign(f, legacy, "y", "wrong-app"),
                resign(f, legacy, "roomproof", "00", "00"),
                new SignedNostrEvent(
                    legacy.getId(),
                    legacy.getPubkey(),
                    legacy.getKind(),
                    legacy.getContent() + "tampered",
                    legacy.getCreatedAt(),
                    legacy.getSignature(),
                    legacy.getTagRows()
                )
            )) expectFailure(() -> f.codec.decode(invalid, f.scope, f.presence(), f.room, now).await());
            SignedNostrEvent expired = resign(f, f.event(now.minusSeconds(120), 2), "d", rawAddress(f.scope, SESSION_A));
            expectFailure(() -> f.codec.decode(expired, f.scope, f.presence(), f.room, now).await());
        } finally {
            signer.close().await();
        }
    }

    @Test(timeout = 12000)
    public void controlPlaneSelectsVerifiedPresenceByLegacyAndCompactAliases() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair()), receiver = new NostrKeyPairSigner(
            new NostrKeyPair()
        );
        CapturingPool pool = new CapturingPool();
        TopologyControlPlane control = null;
        try {
            Fixture f = new Fixture(signer, APP, APP + ":1", SESSION_A);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            NostrRTCLocalPeer local = new NostrRTCLocalPeer(
                RTCSettings.getDefault(APP, APP + ":1").withStunServers(Collections.emptyList()),
                receiver,
                "receiver-session",
                f.room,
                null
            );
            control =
                new TopologyControlPlane(
                    f.scope,
                    local,
                    f.room,
                    new NostrKeyPair(),
                    pool,
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(5)
                );
            TopologyControlPlane observed = control;
            SignedNostrEvent legacy = resign(f, f.event(now, 1), "d", rawAddress(f.scope, SESSION_A));
            control.acceptEvent(legacy, now);
            assertTrue(control.getSnapshots(now).isEmpty());
            SignedNostrEvent presence = new NostrRTCConnectSignal(signer, f.room, f.presence(), now.plusSeconds(60), "")
                .toEvent(null)
                .await();
            assertTrue(presence.verify());
            control.updatePresences(List.of(new NostrRTCConnectSignal(signer, f.room, presence)), now);
            SignedNostrEvent bad = resign(f, legacy, "roomproof", "00", "00");
            control.acceptEvent(bad, now);
            assertTrue(control.getSnapshots(now).isEmpty());
            control.acceptEvent(legacy, now);
            await(() -> observed.getSnapshots(now).size() == 1);
            assertEquals(1L, control.getSnapshots(now).iterator().next().getRevision());
            control.acceptEvent(f.event(now.plusSeconds(1), 2), now.plusSeconds(1));
            await(() -> observed.getSnapshots(now).iterator().next().getRevision() == 2L);
            control.close();
            assertTrue(control.getSnapshots(now).isEmpty());
        } finally {
            if (control != null) control.close();
            pool.close();
            signer.close().await();
            receiver.close().await();
        }
    }

    @Test(timeout = 12000)
    public void realPublisherSendsOnlyCompactLongAddressAndNoBrokenLegacyTwin() throws Exception {
        NostrKeyPairSigner signer = new NostrKeyPairSigner(new NostrKeyPair());
        CapturingPool pool = new CapturingPool();
        TopologyControlPlane control = null;
        try {
            Fixture f = new Fixture(signer, APP, APP + ":1", SESSION_A);
            control =
                new TopologyControlPlane(
                    f.scope,
                    f.local,
                    f.room,
                    new NostrKeyPair(),
                    pool,
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(5)
                );
            SignedNostrEvent event = control.publishNow(Instant.now()).await();
            assertEquals(1, pool.events.size());
            assertSame(event, pool.events.get(0));
            assertTrue(event.verify());
            assertEquals(76, event.getFirstTagFirstValue("d").getBytes(StandardCharsets.UTF_8).length);
            assertEquals(f.scope.topologyAddress(SESSION_A), event.getFirstTagFirstValue("d"));
            f.codec.decode(event, f.scope, f.presence(), f.room, Instant.now()).await();
        } finally {
            if (control != null) control.close();
            pool.close();
            signer.close().await();
        }
    }

    // Independent byte encoder and SHA256 oracle, not production canonicalBytes/topologyAddress.
    static String expectedAddress(RoutingScope scope, String session, String domain) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.write(domain.getBytes(StandardCharsets.US_ASCII));
        String hex = scope.getRoomPubkey().asHex();
        byte[] pub = new byte[hex.length() / 2];
        for (int i = 0; i < pub.length; i++) pub[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        data.writeInt(pub.length);
        data.write(pub);
        for (String value : List.of(scope.getProtocolId(), scope.getApplicationId(), session)) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            data.writeInt(utf8.length);
            data.write(utf8);
        }
        data.flush();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        StringBuilder encoded = new StringBuilder("dc-topology:");
        for (byte value : hash) encoded.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return encoded.toString();
    }

    static SignedNostrEvent resign(Fixture f, SignedNostrEvent event, String tag, String... values) throws Exception {
        return f.signer
            .sign(new UnsignedNostrEvent(new HashMap<String, Object>(event.toMap())).replaceTag(tag, values))
            .await();
    }

    interface Checked {
        void run() throws Exception;
    }

    static void expectFailure(Checked action) throws Exception {
        try {
            action.run();
            fail("untrusted or mismatched topology must fail");
        } catch (Exception expected) {}
    }

    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(2L);
        assertTrue("actual asynchronous topology admission", condition.getAsBoolean());
    }

    static final class CapturingPool extends NostrPool {

        final List<SignedNostrEvent> events = new CopyOnWriteArrayList<SignedNostrEvent>();

        @Override
        public AsyncTask<List<AsyncTask<NostrMessageAck>>> publish(SignedNostrEvent event) {
            events.add(event);
            return AsyncTask.completed(Collections.emptyList());
        }
    }

    static String rawAddress(RoutingScope scope, String session) {
        return (
            "dc-topology:" +
            scope.getRoomPubkey().asHex() +
            ":" +
            scope.getProtocolId() +
            ":" +
            scope.getApplicationId() +
            ":" +
            session
        );
    }

    /**
     * Behavioral relay index model, not a live relay. Exact upstream6fe449f:
     * constants.h MAX_INDEXED_TAG_VAL_SIZE255; events.cpp31-52 ignores longer d then
     * appends empty d; writeEvents246-265 replaces by author/indexed-d/kind using NIP01 order.
     * https://github.com/hoytech/strfry/blob/6fe449f/src/events.cpp
     */
    static final class Strfry255Index {

        final Map<String, SignedNostrEvent> latest = new HashMap<String, SignedNostrEvent>();
        int rejected;

        boolean accept(SignedNostrEvent event) {
            String d = "";
            for (List<String> tag : event.getTagRows()) if (
                tag.size() >= 2 && tag.get(0).equals("d") && tag.get(1).getBytes(StandardCharsets.UTF_8).length <= 255
            ) {
                d = tag.get(1);
                break;
            }
            String key = event.getPubkey().asHex() + ":" + event.getKind() + ":" + d;
            SignedNostrEvent old = latest.get(key);
            if (
                old != null &&
                (
                    event.getCreatedAt().isBefore(old.getCreatedAt()) ||
                    (event.getCreatedAt().equals(old.getCreatedAt()) && event.getId().compareTo(old.getId()) > 0)
                )
            ) {
                rejected++;
                return false;
            }
            latest.put(key, event);
            return true;
        }
    }

    static final class Fixture {

        final NostrKeyPair room = new NostrKeyPair();
        final NostrKeyPairSigner signer;
        final NostrRTCLocalPeer local;
        final RoutingScope scope;
        final TopologyEventCodec codec = new TopologyEventCodec();

        Fixture(NostrKeyPairSigner signer, String app, String protocol, String session) {
            this.signer = signer;
            local =
                new NostrRTCLocalPeer(
                    RTCSettings.getDefault(app, protocol).withStunServers(Collections.emptyList()),
                    signer,
                    session,
                    room,
                    null
                );
            scope = new RoutingScope(room.getPublicKey(), protocol, app);
        }

        SignedNostrEvent event(Instant issued, long revision) throws Exception {
            TopologySnapshot snapshot = new TopologySnapshot(
                scope,
                local.getPubkey(),
                local.getSessionId(),
                revision,
                NodeId.derive(scope, local.getPubkey(), local.getSessionId()),
                new NostrKeyPair().getPublicKey(),
                issued,
                issued.plusSeconds(60),
                List.of()
            );
            return codec.encode(snapshot, local, room, issued).await();
        }

        NostrRTCPeer presence() {
            return new NostrRTCPeer(
                local.getPubkey(),
                local.getApplicationId(),
                local.getProtocolId(),
                local.getSessionId(),
                room.getPublicKey(),
                null
            );
        }
    }
}
