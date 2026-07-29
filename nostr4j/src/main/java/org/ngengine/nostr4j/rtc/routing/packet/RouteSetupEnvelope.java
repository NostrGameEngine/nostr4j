/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.packet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Objects;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;

/**
 * Big-endian dc4 route-setup envelope. The ciphertext is retained as a
 * read-only view when decoding.
 */
public final class RouteSetupEnvelope {

    private static final int MAGIC = 0x44433453;
    private static final byte VERSION = 1;
    private static final byte TYPE_SETUP = 1;
    private static final int FIXED_BYTES = 4 + 1 + 1 + CircuitId.SIZE * 2 + 8 + 1 + 32 + 4;

    private final CircuitId setupId;
    private final CircuitId circuitId;
    private final Instant expiresAt;
    private final int remainingHops;
    private final NostrPublicKey ephemeralPublicKey;
    private final ByteBuffer ciphertext;

    public RouteSetupEnvelope(
        CircuitId setupId,
        CircuitId circuitId,
        Instant expiresAt,
        int remainingHops,
        NostrPublicKey ephemeralPublicKey,
        ByteBuffer ciphertext
    ) {
        if (remainingHops < 1 || remainingHops > RoutingLimits.MAX_ROUTE_HOPS) {
            throw new IllegalArgumentException("Invalid route setup hop count");
        }
        if (ciphertext == null || !ciphertext.hasRemaining()) {
            throw new IllegalArgumentException("Route setup ciphertext is empty");
        }
        if (FIXED_BYTES + ciphertext.remaining() > RoutingLimits.MAX_ROUTE_SETUP_BYTES) {
            throw new IllegalArgumentException("Route setup exceeds size limit");
        }
        this.setupId = Objects.requireNonNull(setupId);
        this.circuitId = Objects.requireNonNull(circuitId);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.remainingHops = remainingHops;
        this.ephemeralPublicKey = Objects.requireNonNull(ephemeralPublicKey);
        this.ciphertext = ciphertext.asReadOnlyBuffer();
    }

    public CircuitId getSetupId() {
        return setupId;
    }

    public CircuitId getCircuitId() {
        return circuitId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getRemainingHops() {
        return remainingHops;
    }

    public NostrPublicKey getEphemeralPublicKey() {
        return ephemeralPublicKey;
    }

    public ByteBuffer getCiphertext() {
        return ciphertext.asReadOnlyBuffer();
    }

    public ByteBuffer encode() {
        ByteBuffer encrypted = ciphertext.asReadOnlyBuffer();
        ByteBuffer out = ByteBuffer.allocate(FIXED_BYTES + encrypted.remaining()).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put(VERSION);
        out.put(TYPE_SETUP);
        out.put(setupId.toByteArray());
        out.put(circuitId.toByteArray());
        out.putLong(expiresAt.getEpochSecond());
        out.put((byte) remainingHops);
        out.put(ephemeralPublicKey._array());
        out.putInt(encrypted.remaining());
        out.put(encrypted);
        out.flip();
        return out.asReadOnlyBuffer();
    }

    public static RouteSetupEnvelope decode(ByteBuffer input) {
        return decode(input, null);
    }

    public static RouteSetupEnvelope decode(ByteBuffer input, Instant now) {
        ByteBuffer data = input.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        if (data.remaining() < FIXED_BYTES || data.remaining() > RoutingLimits.MAX_ROUTE_SETUP_BYTES) {
            throw new IllegalArgumentException("Invalid route setup envelope length");
        }
        if (data.getInt() != MAGIC) throw new IllegalArgumentException("Invalid route setup magic");
        if (data.get() != VERSION) throw new IllegalArgumentException("Invalid route setup version");
        if (data.get() != TYPE_SETUP) throw new IllegalArgumentException("Invalid route setup type");
        CircuitId setup = CircuitId.read(data);
        CircuitId circuit = CircuitId.read(data);
        long expirySeconds = data.getLong();
        Instant expiry = Instant.ofEpochSecond(expirySeconds);
        if (
            now != null && (!expiry.isAfter(now) || expiry.isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS)))
        ) {
            throw new IllegalArgumentException("Invalid route setup expiry");
        }
        int remaining = data.get() & 0xff;
        byte[] publicKey = new byte[32];
        data.get(publicKey);
        int encryptedLength = data.getInt();
        if (encryptedLength < 1 || encryptedLength != data.remaining()) {
            throw new IllegalArgumentException("Invalid route setup ciphertext length");
        }
        ByteBuffer ciphertext = data.slice().asReadOnlyBuffer();
        return new RouteSetupEnvelope(setup, circuit, expiry, remaining, NostrPublicKey.fromBytes(publicKey), ciphertext);
    }
}
