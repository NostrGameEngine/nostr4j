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

public final class StatelessControlEnvelope {

    private static final int MAGIC = 0x44433443;
    private static final byte VERSION = 1;
    private static final int FIXED_BYTES = 4 + 1 + CircuitId.SIZE + 8 + 1 + 32 + 4;

    private final CircuitId messageId;
    private final Instant expiresAt;
    private final int remainingHops;
    private final NostrPublicKey ephemeralPublicKey;
    private final ByteBuffer ciphertext;
    private final ByteBuffer wireView;

    public StatelessControlEnvelope(
        CircuitId messageId,
        Instant expiresAt,
        int remainingHops,
        NostrPublicKey ephemeralPublicKey,
        ByteBuffer ciphertext
    ) {
        this(messageId, expiresAt, remainingHops, ephemeralPublicKey, ciphertext, null);
    }

    private StatelessControlEnvelope(
        CircuitId messageId,
        Instant expiresAt,
        int remainingHops,
        NostrPublicKey ephemeralPublicKey,
        ByteBuffer ciphertext,
        ByteBuffer wireView
    ) {
        if (remainingHops < 1 || remainingHops > RoutingLimits.MAX_ROUTE_HOPS) {
            throw new IllegalArgumentException("Invalid stateless control hop count");
        }
        if (ciphertext == null || !ciphertext.hasRemaining()) {
            throw new IllegalArgumentException("Stateless control ciphertext is empty");
        }
        if (FIXED_BYTES + ciphertext.remaining() > RoutingLimits.MAX_ROUTE_SETUP_BYTES) {
            throw new IllegalArgumentException("Stateless control packet exceeds size limit");
        }
        this.messageId = Objects.requireNonNull(messageId);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.remainingHops = remainingHops;
        this.ephemeralPublicKey = Objects.requireNonNull(ephemeralPublicKey);
        this.ciphertext = ciphertext.asReadOnlyBuffer();
        this.wireView = wireView == null ? null : wireView.asReadOnlyBuffer();
    }

    public CircuitId getMessageId() {
        return messageId;
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
        out.put(messageId.toByteArray());
        out.putLong(expiresAt.getEpochSecond());
        out.put((byte) remainingHops);
        out.put(ephemeralPublicKey._array());
        out.putInt(encrypted.remaining());
        out.put(encrypted);
        out.flip();
        return out.asReadOnlyBuffer();
    }

    public static StatelessControlEnvelope decode(ByteBuffer input, Instant now) {
        ByteBuffer full = input.slice().order(ByteOrder.BIG_ENDIAN);
        if (full.remaining() < FIXED_BYTES || full.remaining() > RoutingLimits.MAX_ROUTE_SETUP_BYTES) {
            throw new IllegalArgumentException("Invalid stateless control length");
        }
        ByteBuffer data = full.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (data.getInt() != MAGIC) throw new IllegalArgumentException("Invalid stateless control magic");
        if (data.get() != VERSION) throw new IllegalArgumentException("Invalid stateless control version");
        CircuitId messageId = CircuitId.read(data);
        Instant expiry = Instant.ofEpochSecond(data.getLong());
        if (!expiry.isAfter(now)) throw new IllegalArgumentException("Stateless control packet is expired");
        if (expiry.isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Stateless control expiry exceeds limit");
        }
        int remaining = data.get() & 0xff;
        byte[] publicKey = new byte[32];
        data.get(publicKey);
        int ciphertextLength = data.getInt();
        if (ciphertextLength < 1 || ciphertextLength != data.remaining()) {
            throw new IllegalArgumentException("Invalid stateless control ciphertext length");
        }
        return new StatelessControlEnvelope(
            messageId,
            expiry,
            remaining,
            NostrPublicKey.fromBytes(publicKey),
            data.slice().asReadOnlyBuffer(),
            full
        );
    }
}
