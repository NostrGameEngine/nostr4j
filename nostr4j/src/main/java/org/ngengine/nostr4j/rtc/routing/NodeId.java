/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class NodeId implements Comparable<NodeId> {

    public static final int SIZE = 32;
    private static final byte[] DOMAIN = "nip-dc-routing-node-v1".getBytes(StandardCharsets.UTF_8);
    private final byte[] bytes;

    private NodeId(byte[] bytes) {
        if (bytes.length != SIZE) {
            throw new IllegalArgumentException("NodeId must contain exactly 32 bytes");
        }
        this.bytes = bytes.clone();
    }

    public static NodeId derive(RoutingScope scope, NostrPublicKey peerPubkey, String sessionId) {
        Objects.requireNonNull(scope, "Routing scope cannot be null");
        Objects.requireNonNull(peerPubkey, "Peer pubkey cannot be null");
        Objects.requireNonNull(sessionId, "Session id cannot be null");
        ByteBuffer scopeBytes = scope.canonicalBytes();
        byte[] peer = peerPubkey._array();
        byte[] session = sessionId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer encoded = ByteBuffer.allocate(
            DOMAIN.length + scopeBytes.remaining() + Integer.BYTES + peer.length + Integer.BYTES + session.length
        );
        encoded.put(DOMAIN);
        encoded.put(scopeBytes);
        RoutingScope.putLengthPrefixed(encoded, peer);
        RoutingScope.putLengthPrefixed(encoded, session);
        return new NodeId(NGEPlatform.get().sha256(encoded.array()));
    }

    public static NodeId fromHex(String hex) {
        ByteBuffer encoded = NGEUtils.hexToBytes(hex);
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return new NodeId(bytes);
    }

    public byte[] toByteArray() {
        return bytes.clone();
    }

    public String asHex() {
        return NGEUtils.bytesToHex(bytes);
    }

    @Override
    public int compareTo(NodeId other) {
        for (int i = 0; i < bytes.length; i++) {
            int left = bytes[i] & 0xff;
            int right = other.bytes[i] & 0xff;
            if (left != right) {
                return left < right ? -1 : 1;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NodeId && Arrays.equals(bytes, ((NodeId) other).bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return asHex();
    }
}
