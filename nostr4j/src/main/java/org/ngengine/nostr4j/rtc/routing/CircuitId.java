/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class CircuitId implements Comparable<CircuitId> {

    public static final int SIZE = 16;
    private final byte[] bytes;

    private CircuitId(byte[] bytes) {
        if (bytes.length != SIZE) throw new IllegalArgumentException("Circuit id must contain 16 bytes");
        this.bytes = bytes.clone();
    }

    public static CircuitId random() {
        byte[] bytes = NGEPlatform.get().randomBytes(SIZE);
        if (NGEUtils.allZeroes(bytes)) bytes[SIZE - 1] = 1;
        return new CircuitId(bytes);
    }

    public static CircuitId fromBytes(byte[] bytes) {
        return new CircuitId(bytes);
    }

    public static CircuitId read(ByteBuffer input) {
        if (input.remaining() < SIZE) throw new IllegalArgumentException("Truncated circuit id");
        byte[] bytes = new byte[SIZE];
        input.get(bytes);
        return new CircuitId(bytes);
    }

    public byte[] toByteArray() {
        return bytes.clone();
    }

    public String asHex() {
        return NGEUtils.bytesToHex(bytes);
    }

    @Override
    public int compareTo(CircuitId other) {
        for (int index = 0; index < bytes.length; index++) {
            int comparison = Integer.compare(bytes[index] & 0xff, other.bytes[index] & 0xff);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CircuitId && Arrays.equals(bytes, ((CircuitId) other).bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
