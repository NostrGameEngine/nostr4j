/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.RoutingProtocol;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostr4j.utils.NostrRoomProof;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class TopologyEventCodec {

    public AsyncTask<SignedNostrEvent> encode(
        TopologySnapshot snapshot,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        Instant createdAt
    ) {
        validateLocalSnapshot(snapshot, localPeer, roomKeyPair);
        if (!snapshot.getIssuedAt().equals(createdAt)) {
            throw new IllegalArgumentException("Topology created_at must match issuedAt");
        }
        String plaintext = encodeContent(snapshot);
        NostrSigner signer = localPeer.getSigner();
        return signer
            .encrypt(plaintext, roomKeyPair.getPublicKey(), NostrSigner.EncryptAlgo.NIP44)
            .compose(encrypted -> {
                if (encrypted.getBytes(StandardCharsets.UTF_8).length > RoutingLimits.MAX_TOPOLOGY_EVENT_BYTES) {
                    return AsyncTask.failed(new IllegalArgumentException("Encrypted topology event exceeds size limit"));
                }
                UnsignedNostrEvent event = new UnsignedNostrEvent()
                    .withKind(RoutingProtocol.TOPOLOGY_EVENT_KIND)
                    .createdAt(createdAt)
                    .withContent(encrypted)
                    .withTag("d", snapshot.getScope().topologyAddress(snapshot.getSessionId()))
                    .withTag("t", RoutingProtocol.TOPOLOGY_EVENT_TYPE)
                    .withTag("version", RoutingProtocol.VERSION)
                    .withTag("P", snapshot.getScope().getRoomPubkey().asHex())
                    .withTag("i", snapshot.getScope().getProtocolId())
                    .withTag("y", snapshot.getScope().getApplicationId())
                    .withTag("revision", Long.toUnsignedString(snapshot.getRevision()))
                    .withTag("expiration", Long.toString(snapshot.getExpiresAt().getEpochSecond()));
                return signer
                    .getPublicKey()
                    .compose(author -> {
                        String challenge = roomProofChallenge(snapshot, encrypted);
                        return NostrRoomProof
                            .sign(roomKeyPair, createdAt, RoutingProtocol.TOPOLOGY_EVENT_KIND, author, challenge)
                            .then(signature -> {
                                String proofId = NostrRoomProof.computeId(
                                    roomKeyPair.getPublicKey(),
                                    createdAt,
                                    RoutingProtocol.TOPOLOGY_EVENT_KIND,
                                    author,
                                    challenge
                                );
                                event.withTag("roomproof", proofId, signature);
                                return event;
                            });
                    })
                    .compose(signer::sign);
            });
    }

    public AsyncTask<TopologySnapshot> decode(
        SignedNostrEvent event,
        RoutingScope expectedScope,
        NostrRTCPeer expectedPresence,
        NostrKeyPair roomKeyPair,
        Instant now
    ) {
        Objects.requireNonNull(event, "Topology event cannot be null");
        Objects.requireNonNull(expectedScope, "Expected routing scope cannot be null");
        Objects.requireNonNull(expectedPresence, "Expected presence cannot be null");
        Objects.requireNonNull(roomKeyPair, "Room keypair cannot be null");
        Objects.requireNonNull(now, "Current time cannot be null");
        validateEnvelope(event, expectedScope, expectedPresence, now);
        long revision = parsePositiveLong(event.getFirstTagFirstValue("revision"), "revision");
        Instant expiresAt = Instant.ofEpochSecond(parsePositiveLong(event.getFirstTagFirstValue("expiration"), "expiration"));
        String encrypted = NGEUtils.safeString(event.getContent());
        TopologySnapshot challengeSnapshot = new TopologySnapshot(
            expectedScope,
            event.getPubkey(),
            expectedPresence.getSessionId(),
            revision,
            NodeId.derive(expectedScope, event.getPubkey(), expectedPresence.getSessionId()),
            event.getPubkey(),
            event.getCreatedAt(),
            expiresAt,
            List.of()
        );
        verifyRoomProof(event, challengeSnapshot, encrypted);
        NostrKeyPairSigner roomSigner = new NostrKeyPairSigner(roomKeyPair);
        return roomSigner
            .decrypt(encrypted, event.getPubkey(), NostrSigner.EncryptAlgo.NIP44)
            .then(plaintext ->
                decodeContent(
                    plaintext,
                    expectedScope,
                    event.getPubkey(),
                    expectedPresence.getSessionId(),
                    revision,
                    event.getCreatedAt(),
                    expiresAt
                )
            );
    }

    private static void validateLocalSnapshot(
        TopologySnapshot snapshot,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair
    ) {
        if (!snapshot.getPeerPubkey().equals(localPeer.getPubkey())) {
            throw new IllegalArgumentException("Topology author does not match local peer");
        }
        if (!snapshot.getSessionId().equals(localPeer.getSessionId())) {
            throw new IllegalArgumentException("Topology session does not match local peer");
        }
        if (!snapshot.getScope().getRoomPubkey().equals(roomKeyPair.getPublicKey())) {
            throw new IllegalArgumentException("Topology room does not match room keypair");
        }
        NodeId expected = NodeId.derive(snapshot.getScope(), localPeer.getPubkey(), localPeer.getSessionId());
        if (!expected.equals(snapshot.getNodeId())) {
            throw new IllegalArgumentException("Topology NodeId does not match local peer session");
        }
    }

    private static void validateEnvelope(SignedNostrEvent event, RoutingScope scope, NostrRTCPeer presence, Instant now) {
        String content = event.getContent();
        if (content == null) {
            throw new IllegalArgumentException("Topology event content is missing");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > RoutingLimits.MAX_TOPOLOGY_EVENT_BYTES) {
            throw new IllegalArgumentException("Topology event exceeds size limit");
        }
        try {
            if (!event.verify()) {
                throw new IllegalArgumentException("Topology event signature is invalid");
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("Topology event signature verification failed", error);
        }
        if (event.getKind() != RoutingProtocol.TOPOLOGY_EVENT_KIND) {
            throw new IllegalArgumentException("Unexpected topology event kind");
        }
        if (!RoutingProtocol.TOPOLOGY_EVENT_TYPE.equals(event.getFirstTagFirstValue("t"))) {
            throw new IllegalArgumentException("Unexpected topology event type");
        }
        if (!RoutingProtocol.VERSION.equals(event.getFirstTagFirstValue("version"))) {
            throw new IllegalArgumentException("Unsupported topology protocol version");
        }
        if (!scope.getRoomPubkey().asHex().equals(event.getFirstTagFirstValue("P"))) {
            throw new IllegalArgumentException("Topology room scope mismatch");
        }
        if (!scope.getProtocolId().equals(event.getFirstTagFirstValue("i"))) {
            throw new IllegalArgumentException("Topology protocol scope mismatch");
        }
        if (!scope.getApplicationId().equals(event.getFirstTagFirstValue("y"))) {
            throw new IllegalArgumentException("Topology application scope mismatch");
        }
        if (!scope.topologyAddress(presence.getSessionId()).equals(event.getFirstTagFirstValue("d"))) {
            throw new IllegalArgumentException("Topology session scope mismatch");
        }
        if (!presence.getPubkey().equals(event.getPubkey())) {
            throw new IllegalArgumentException("Topology author does not match room presence");
        }
        if (
            event.isExpired() ||
            !Instant.ofEpochSecond(parsePositiveLong(event.getFirstTagFirstValue("expiration"), "expiration")).isAfter(now)
        ) {
            throw new IllegalArgumentException("Topology event is expired");
        }
    }

    private static void verifyRoomProof(SignedNostrEvent event, TopologySnapshot snapshot, String encrypted) {
        if (event.getFirstTag("roomproof") == null || event.getFirstTag("roomproof").size() < 2) {
            throw new IllegalArgumentException("Missing topology roomproof");
        }
        String challenge = roomProofChallenge(snapshot, encrypted);
        boolean valid = NostrRoomProof.verify(
            snapshot.getScope().getRoomPubkey(),
            event.getCreatedAt(),
            event.getKind(),
            event.getPubkey(),
            challenge,
            event.getFirstTag("roomproof").get(0),
            event.getFirstTag("roomproof").get(1)
        );
        if (!valid) {
            throw new IllegalArgumentException("Invalid topology roomproof");
        }
    }

    private static String roomProofChallenge(TopologySnapshot snapshot, String encrypted) {
        String contentHash = NGEUtils.bytesToHex(NGEPlatform.get().sha256(encrypted.getBytes(StandardCharsets.UTF_8)));
        return NGEPlatform
            .get()
            .toJSON(
                List.of(
                    snapshot.getScope().getRoomPubkey().asHex(),
                    snapshot.getScope().getProtocolId(),
                    snapshot.getScope().getApplicationId(),
                    snapshot.getSessionId(),
                    Long.toUnsignedString(snapshot.getRevision()),
                    Long.toString(snapshot.getExpiresAt().getEpochSecond()),
                    contentHash
                )
            );
    }

    private static String encodeContent(TopologySnapshot snapshot) {
        Map<String, Object> root = new HashMap<String, Object>();
        root.put("formatVersion", RoutingProtocol.VERSION);
        root.put("revision", Long.valueOf(snapshot.getRevision()));
        root.put("nodeId", snapshot.getNodeId().asHex());
        root.put("routingPublicKey", snapshot.getRoutingPublicKey().asHex());
        root.put("issuedAt", Long.valueOf(snapshot.getIssuedAt().getEpochSecond()));
        root.put("expiresAt", Long.valueOf(snapshot.getExpiresAt().getEpochSecond()));
        List<Map<String, Object>> neighbors = new ArrayList<Map<String, Object>>();
        for (TopologyNeighbor neighbor : snapshot.getNeighbors()) {
            Map<String, Object> encoded = new HashMap<String, Object>();
            encoded.put("nodeId", neighbor.getNodeId().asHex());
            encoded.put("pubkey", neighbor.getPubkey().asHex());
            encoded.put("sessionId", neighbor.getSessionId());
            encoded.put("edgeId", neighbor.getEdgeId().asHex());
            encoded.put("transport", neighbor.getTransport().name());
            neighbors.add(encoded);
        }
        root.put("neighbors", neighbors);
        return NGEPlatform.get().toJSON(root);
    }

    @SuppressWarnings("unchecked")
    private static TopologySnapshot decodeContent(
        String plaintext,
        RoutingScope scope,
        NostrPublicKey author,
        String sessionId,
        long expectedRevision,
        Instant createdAt,
        Instant expectedExpiry
    ) {
        if (plaintext.getBytes(StandardCharsets.UTF_8).length > RoutingLimits.MAX_TOPOLOGY_EVENT_BYTES) {
            throw new IllegalArgumentException("Decrypted topology content exceeds size limit");
        }
        Map<String, Object> root = NGEPlatform.get().fromJSON(plaintext, Map.class);
        if (root == null) {
            throw new IllegalArgumentException("Topology content must be a JSON object");
        }
        if (!RoutingProtocol.VERSION.equals(string(root.get("formatVersion"), "formatVersion"))) {
            throw new IllegalArgumentException("Unsupported topology content version");
        }
        long revision = number(root.get("revision"), "revision");
        if (revision != expectedRevision) {
            throw new IllegalArgumentException("Topology revision does not match public envelope");
        }
        Instant issuedAt = Instant.ofEpochSecond(number(root.get("issuedAt"), "issuedAt"));
        Instant expiresAt = Instant.ofEpochSecond(number(root.get("expiresAt"), "expiresAt"));
        if (!issuedAt.equals(createdAt) || !expiresAt.equals(expectedExpiry)) {
            throw new IllegalArgumentException("Topology timestamps do not match public envelope");
        }
        NodeId localNode = NodeId.fromHex(string(root.get("nodeId"), "nodeId"));
        NodeId expectedNode = NodeId.derive(scope, author, sessionId);
        if (!localNode.equals(expectedNode)) {
            throw new IllegalArgumentException("Topology NodeId does not match author session");
        }
        NostrPublicKey routingPublicKey = NostrPublicKey.fromHex(string(root.get("routingPublicKey"), "routingPublicKey"));
        Object neighborValue = root.get("neighbors");
        if (!(neighborValue instanceof List)) {
            throw new IllegalArgumentException("Topology neighbors must be a list");
        }
        List<?> encodedNeighbors = (List<?>) neighborValue;
        if (encodedNeighbors.size() > RoutingLimits.MAX_TOPOLOGY_NEIGHBORS) {
            throw new IllegalArgumentException("Topology neighbor limit exceeded");
        }
        List<TopologyNeighbor> neighbors = new ArrayList<TopologyNeighbor>();
        Set<NodeId> seen = new HashSet<NodeId>();
        for (Object value : encodedNeighbors) {
            if (!(value instanceof Map)) {
                throw new IllegalArgumentException("Malformed topology neighbor");
            }
            Map<String, Object> encoded = (Map<String, Object>) value;
            NostrPublicKey pubkey = NostrPublicKey.fromHex(string(encoded.get("pubkey"), "neighbor.pubkey"));
            String neighborSession = string(encoded.get("sessionId"), "neighbor.sessionId");
            NodeId node = NodeId.fromHex(string(encoded.get("nodeId"), "neighbor.nodeId"));
            if (!node.equals(NodeId.derive(scope, pubkey, neighborSession))) {
                throw new IllegalArgumentException("Neighbor NodeId does not match peer session");
            }
            if (!seen.add(node)) {
                throw new IllegalArgumentException("Duplicate topology neighbor");
            }
            EdgeId edge = EdgeId.fromHex(string(encoded.get("edgeId"), "neighbor.edgeId"));
            if (!edge.equals(EdgeId.derive(scope, localNode, node))) {
                throw new IllegalArgumentException("Neighbor edge id is invalid");
            }
            TopologyTransport transport = TopologyTransport.valueOf(string(encoded.get("transport"), "neighbor.transport"));
            neighbors.add(new TopologyNeighbor(node, pubkey, neighborSession, edge, transport));
        }
        return new TopologySnapshot(
            scope,
            author,
            sessionId,
            revision,
            localNode,
            routingPublicKey,
            issuedAt,
            expiresAt,
            neighbors
        );
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException("Missing or invalid topology field: " + field);
        }
        return (String) value;
    }

    private static long number(Object value, String field) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Missing or invalid topology number: " + field);
        }
        if (value instanceof Double || value instanceof Float) {
            double decimal = ((Number) value).doubleValue();
            if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)) {
                throw new IllegalArgumentException("Topology number must be an integer: " + field);
            }
        }
        long result = ((Number) value).longValue();
        if (result <= 0L) {
            throw new IllegalArgumentException("Topology number must be positive: " + field);
        }
        return result;
    }

    private static long parsePositiveLong(String value, String field) {
        try {
            long parsed = Long.parseUnsignedLong(value);
            if (parsed <= 0L) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid topology " + field, error);
        }
    }
}
