/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class RoutingScope {

    private static final int MAX_LEGACY_TOPOLOGY_ADDRESS_BYTES = 255;
    private static final byte[] TOPOLOGY_ADDRESS_DOMAIN = "nip-dc-topology-address-v1".getBytes(StandardCharsets.US_ASCII);

    private final NostrPublicKey roomPubkey;
    private final String protocolId;
    private final String applicationId;

    public RoutingScope(NostrPublicKey roomPubkey, String protocolId, String applicationId) {
        this.roomPubkey = Objects.requireNonNull(roomPubkey, "Room pubkey cannot be null");
        this.protocolId = Objects.requireNonNull(protocolId, "Protocol id cannot be null");
        this.applicationId = Objects.requireNonNull(applicationId, "Application id cannot be null");
    }

    public NostrPublicKey getRoomPubkey() {
        return roomPubkey;
    }

    public String getProtocolId() {
        return protocolId;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public ByteBuffer canonicalBytes() {
        byte[] room = roomPubkey._array();
        byte[] protocol = protocolId.getBytes(StandardCharsets.UTF_8);
        byte[] application = applicationId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer encoded = ByteBuffer.allocate(
            Integer.BYTES + room.length + Integer.BYTES + protocol.length + Integer.BYTES + application.length
        );
        putLengthPrefixed(encoded, room);
        putLengthPrefixed(encoded, protocol);
        putLengthPrefixed(encoded, application);
        encoded.flip();
        return encoded.asReadOnlyBuffer();
    }

    /**
     * Returns the canonical 76-byte topology address for this scope and native session.
     */
    public String topologyAddress(String sessionId) {
        Objects.requireNonNull(sessionId, "Topology session cannot be null");
        ByteBuffer scope = canonicalBytes();
        byte[] session = sessionId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer encoded = ByteBuffer.allocate(
            TOPOLOGY_ADDRESS_DOMAIN.length + scope.remaining() + Integer.BYTES + session.length
        );
        encoded.put(TOPOLOGY_ADDRESS_DOMAIN);
        encoded.put(scope);
        putLengthPrefixed(encoded, session);
        return RoutingProtocol.TOPOLOGY_EVENT_TYPE + ":" + NGEUtils.bytesToHex(NGEPlatform.get().sha256(encoded.array()));
    }

    /**
     * Returns the original topology address accepted from legacy publishers.
     */
    public String legacyTopologyAddress(String sessionId) {
        Objects.requireNonNull(sessionId, "Topology session cannot be null");
        return (
            RoutingProtocol.TOPOLOGY_EVENT_TYPE +
            ":" +
            roomPubkey.asHex() +
            ":" +
            protocolId +
            ":" +
            applicationId +
            ":" +
            sessionId
        );
    }

    /**
     * Whether a separate legacy publication fits the 255-byte compatibility limit.
     */
    public boolean canPublishLegacyTopology(String sessionId) {
        return legacyTopologyAddress(sessionId).getBytes(StandardCharsets.UTF_8).length <= MAX_LEGACY_TOPOLOGY_ADDRESS_BYTES;
    }

    static void putLengthPrefixed(ByteBuffer target, byte[] value) {
        target.putInt(value.length);
        target.put(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RoutingScope)) return false;
        RoutingScope that = (RoutingScope) other;
        return (
            roomPubkey.equals(that.roomPubkey) && protocolId.equals(that.protocolId) && applicationId.equals(that.applicationId)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomPubkey, protocolId, applicationId);
    }
}
