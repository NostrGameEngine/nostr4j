/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class EdgeId {

    public static final int SIZE = 32;
    private static final byte[] DOMAIN = "nip-dc-routing-edge-v1".getBytes(StandardCharsets.UTF_8);
    private final byte[] bytes;

    private EdgeId(byte[] bytes) {
        if (bytes.length != SIZE) {
            throw new IllegalArgumentException("EdgeId must contain exactly 32 bytes");
        }
        this.bytes = bytes.clone();
    }

    public static EdgeId derive(RoutingScope scope, NodeId first, NodeId second) {
        NodeId lower = first.compareTo(second) <= 0 ? first : second;
        NodeId upper = first.compareTo(second) <= 0 ? second : first;
        ByteBuffer scopeBytes = scope.canonicalBytes();
        ByteBuffer encoded = ByteBuffer.allocate(DOMAIN.length + scopeBytes.remaining() + NodeId.SIZE * 2);
        encoded.put(DOMAIN);
        encoded.put(scopeBytes);
        encoded.put(lower.toByteArray());
        encoded.put(upper.toByteArray());
        return new EdgeId(NGEPlatform.get().sha256(encoded.array()));
    }

    public static EdgeId fromHex(String hex) {
        ByteBuffer encoded = NGEUtils.hexToBytes(hex);
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return new EdgeId(bytes);
    }

    public String asHex() {
        return NGEUtils.bytesToHex(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EdgeId && Arrays.equals(bytes, ((EdgeId) other).bytes);
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
