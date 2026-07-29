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
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;

/**
 * Immutable dc4 routed frame. Decode retains views over the incoming wire
 * storage so an intermediary can forward without a payload-sized allocation.
 */
public final class RoutedDataFrame {

    private static final int MAGIC = 0x44433444;
    private static final byte VERSION = 1;
    private static final int FLAG_ACK_REQUIRED = 1;
    private static final int FIXED_BYTES = 4 + 1 + 1 + 2 + CircuitId.SIZE + CircuitId.SIZE + 8 + 4;

    private final RoutedFrameType type;
    private final int flags;
    private final CircuitId circuitId;
    private final CircuitId attemptId;
    private final Instant expiresAt;
    private final ByteBuffer ciphertext;
    private final ByteBuffer wireView;

    public RoutedDataFrame(
        RoutedFrameType type,
        int flags,
        CircuitId circuitId,
        CircuitId attemptId,
        Instant expiresAt,
        ByteBuffer ciphertext
    ) {
        this(type, flags, circuitId, attemptId, expiresAt, ciphertext, null);
    }

    private RoutedDataFrame(
        RoutedFrameType type,
        int flags,
        CircuitId circuitId,
        CircuitId attemptId,
        Instant expiresAt,
        ByteBuffer ciphertext,
        ByteBuffer wireView
    ) {
        if ((flags & ~FLAG_ACK_REQUIRED) != 0) throw new IllegalArgumentException("Invalid routed frame flags");
        if (ciphertext == null || !ciphertext.hasRemaining()) throw new IllegalArgumentException("Empty routed ciphertext");
        if (FIXED_BYTES + ciphertext.remaining() > RoutingLimits.MAX_ROUTED_FRAME_BYTES) {
            throw new IllegalArgumentException("Routed frame exceeds size limit");
        }
        this.type = Objects.requireNonNull(type);
        this.flags = flags;
        this.circuitId = Objects.requireNonNull(circuitId);
        this.attemptId = Objects.requireNonNull(attemptId);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.ciphertext = ciphertext.asReadOnlyBuffer();
        this.wireView = wireView == null ? null : wireView.asReadOnlyBuffer();
    }

    public RoutedFrameType getType() {
        return type;
    }

    public int getFlags() {
        return flags;
    }

    public CircuitId getCircuitId() {
        return circuitId;
    }

    public CircuitId getAttemptId() {
        return attemptId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ByteBuffer getCiphertext() {
        return ciphertext.asReadOnlyBuffer();
    }

    public ByteBuffer forwardingView() {
        return wireView == null ? encode() : wireView.asReadOnlyBuffer();
    }

    public ByteBuffer encode() {
        ByteBuffer encrypted = ciphertext.asReadOnlyBuffer();
        ByteBuffer out = ByteBuffer.allocate(FIXED_BYTES + encrypted.remaining()).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put(VERSION);
        out.put((byte) type.wireValue());
        out.putShort((short) flags);
        out.put(circuitId.toByteArray());
        out.put(attemptId.toByteArray());
        out.putLong(expiresAt.getEpochSecond());
        out.putInt(encrypted.remaining());
        out.put(encrypted);
        out.flip();
        return out.asReadOnlyBuffer();
    }

    public static RoutedDataFrame decode(ByteBuffer input, Instant now) {
        ByteBuffer full = input.slice().order(ByteOrder.BIG_ENDIAN);
        if (full.remaining() < FIXED_BYTES || full.remaining() > RoutingLimits.MAX_ROUTED_FRAME_BYTES) {
            throw new IllegalArgumentException("Invalid routed frame length");
        }
        ByteBuffer data = full.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (data.getInt() != MAGIC) throw new IllegalArgumentException("Invalid routed frame magic");
        if (data.get() != VERSION) throw new IllegalArgumentException("Invalid routed frame version");
        RoutedFrameType type = RoutedFrameType.fromWire(data.get() & 0xff);
        int flags = data.getShort() & 0xffff;
        CircuitId circuit = CircuitId.read(data);
        CircuitId attempt = CircuitId.read(data);
        Instant expiry = Instant.ofEpochSecond(data.getLong());
        if (!expiry.isAfter(now)) throw new IllegalArgumentException("Routed frame is expired");
        if (expiry.isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Routed frame expiry exceeds limit");
        }
        int ciphertextLength = data.getInt();
        if (ciphertextLength < 1 || ciphertextLength != data.remaining()) {
            throw new IllegalArgumentException("Invalid routed ciphertext length");
        }
        ByteBuffer ciphertext = data.slice().asReadOnlyBuffer();
        return new RoutedDataFrame(type, flags, circuit, attempt, expiry, ciphertext, full);
    }
}
