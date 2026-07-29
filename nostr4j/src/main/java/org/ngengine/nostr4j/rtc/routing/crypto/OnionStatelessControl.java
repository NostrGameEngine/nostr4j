/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.packet.StatelessControlEnvelope;
import org.ngengine.platform.NGEUtils;

/**
 * Stateless source-routed onion for small control responses and ACKs.
 */
public final class OnionStatelessControl {

    private static final int LAYER_MAGIC = 0x4443344f;
    private static final byte VERSION = 1;
    private static final byte FLAG_FINAL = 1;
    private static final int FIXED_BYTES = 4 + 1 + 1 + 8 + 1 + NodeId.SIZE + NodeId.SIZE + 4;

    public StatelessControlEnvelope build(
        List<NodeId> route,
        Map<NodeId, NostrPublicKey> routingPublicKeys,
        ByteBuffer finalPayload,
        Instant expiresAt,
        Instant now
    ) {
        if (route.size() < 2 || route.size() - 1 > RoutingLimits.MAX_ROUTE_HOPS) {
            throw new IllegalArgumentException("Invalid stateless control route");
        }
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Invalid stateless control expiry");
        }
        ByteBuffer payload = finalPayload.asReadOnlyBuffer();
        if (!payload.hasRemaining()) throw new IllegalArgumentException("Stateless control payload is empty");
        byte[] inner = new byte[payload.remaining()];
        payload.get(inner);
        NostrKeyPair ephemeral = new NostrKeyPair();
        NostrPublicKey ephemeralPublicKey = ephemeral.getPublicKey();
        try {
            for (int index = route.size() - 1; index >= 1; index--) {
                NodeId hop = route.get(index);
                NostrPublicKey hopKey = routingPublicKeys.get(hop);
                if (hopKey == null) throw new IllegalArgumentException("Missing routing key for control hop");
                boolean destination = index == route.size() - 1;
                int remaining = route.size() - index;
                byte[] layer = encodeLayer(
                    expiresAt,
                    remaining,
                    destination ? null : route.get(index + 1),
                    destination ? route.get(0) : null,
                    inner,
                    destination
                );
                byte[] key = Nip44.getConversationKeySync(ephemeral.getPrivateKey(), hopKey);
                try {
                    inner = Nip44.encryptSyncBinary(layer, key);
                } finally {
                    Arrays.fill(key, (byte) 0);
                    Arrays.fill(layer, (byte) 0);
                }
            }
        } finally {
            ephemeral.destroy();
        }
        return new StatelessControlEnvelope(
            CircuitId.random(),
            expiresAt,
            route.size() - 1,
            ephemeralPublicKey,
            ByteBuffer.wrap(inner)
        );
    }

    public PeeledControl peel(StatelessControlEnvelope envelope, NostrPrivateKey localKey, Instant now) {
        if (!envelope.getExpiresAt().isAfter(now)) throw new IllegalArgumentException("Control packet is expired");
        if (envelope.getExpiresAt().isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Control packet expiry exceeds limit");
        }
        ByteBuffer encrypted = envelope.getCiphertext();
        byte[] encryptedBytes = new byte[encrypted.remaining()];
        encrypted.get(encryptedBytes);
        byte[] key = Nip44.getConversationKeySync(localKey, envelope.getEphemeralPublicKey());
        byte[] plaintext;
        try {
            plaintext = Nip44.decryptSyncBinary(encryptedBytes, key);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(encryptedBytes, (byte) 0);
        }
        try {
            ByteBuffer data = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN);
            if (data.remaining() < FIXED_BYTES) throw new IllegalArgumentException("Truncated control onion layer");
            if (data.getInt() != LAYER_MAGIC) throw new IllegalArgumentException("Invalid control onion magic");
            if (data.get() != VERSION) throw new IllegalArgumentException("Invalid control onion version");
            int flags = data.get() & 0xff;
            if ((flags & ~FLAG_FINAL) != 0) throw new IllegalArgumentException("Invalid control onion flags");
            if (data.getLong() != envelope.getExpiresAt().getEpochSecond()) {
                throw new IllegalArgumentException("Control onion expiry mismatch");
            }
            int remaining = data.get() & 0xff;
            if (remaining != envelope.getRemainingHops()) {
                throw new IllegalArgumentException("Control onion hop mismatch");
            }
            byte[] nextBytes = new byte[NodeId.SIZE];
            byte[] originBytes = new byte[NodeId.SIZE];
            data.get(nextBytes);
            data.get(originBytes);
            int innerLength = data.getInt();
            if (innerLength < 1 || innerLength != data.remaining()) {
                throw new IllegalArgumentException("Invalid control onion inner length");
            }
            boolean destination = (flags & FLAG_FINAL) != 0;
            boolean nextZero = allZero(nextBytes);
            boolean originZero = allZero(originBytes);
            byte[] innerBytes = new byte[data.remaining()];
            data.get(innerBytes);
            ByteBuffer inner = ByteBuffer.wrap(innerBytes).asReadOnlyBuffer();
            if (destination) {
                if (remaining != 1 || !nextZero || originZero) {
                    throw new IllegalArgumentException("Invalid final control onion layer");
                }
                return new PeeledControl(true, null, NodeId.fromHex(NGEUtils.bytesToHex(originBytes)), inner, null);
            }
            if (remaining <= 1 || nextZero || !originZero) {
                throw new IllegalArgumentException("Invalid forwarding control onion layer");
            }
            StatelessControlEnvelope forwarded = new StatelessControlEnvelope(
                envelope.getMessageId(),
                envelope.getExpiresAt(),
                remaining - 1,
                envelope.getEphemeralPublicKey(),
                inner
            );
            return new PeeledControl(false, NodeId.fromHex(NGEUtils.bytesToHex(nextBytes)), null, null, forwarded);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static byte[] encodeLayer(
        Instant expiresAt,
        int remaining,
        NodeId next,
        NodeId origin,
        byte[] inner,
        boolean destination
    ) {
        if (FIXED_BYTES + inner.length > 0xffff) {
            throw new IllegalArgumentException("Control onion exceeds NIP-44 limit");
        }
        ByteBuffer out = ByteBuffer.allocate(FIXED_BYTES + inner.length).order(ByteOrder.BIG_ENDIAN);
        out.putInt(LAYER_MAGIC);
        out.put(VERSION);
        out.put(destination ? FLAG_FINAL : (byte) 0);
        out.putLong(expiresAt.getEpochSecond());
        out.put((byte) remaining);
        out.put(next == null ? new byte[NodeId.SIZE] : next.toByteArray());
        out.put(origin == null ? new byte[NodeId.SIZE] : origin.toByteArray());
        out.putInt(inner.length);
        out.put(inner);
        return out.array();
    }

    private static boolean allZero(byte[] bytes) {
        int value = 0;
        for (byte current : bytes) value |= current;
        return value == 0;
    }

    public static final class PeeledControl {

        private final boolean destination;
        private final NodeId nextHop;
        private final NodeId origin;
        private final ByteBuffer finalPayload;
        private final StatelessControlEnvelope forwardedEnvelope;

        private PeeledControl(
            boolean destination,
            NodeId nextHop,
            NodeId origin,
            ByteBuffer finalPayload,
            StatelessControlEnvelope forwardedEnvelope
        ) {
            this.destination = destination;
            this.nextHop = nextHop;
            this.origin = origin;
            this.finalPayload = finalPayload;
            this.forwardedEnvelope = forwardedEnvelope;
        }

        public boolean isDestination() {
            return destination;
        }

        public NodeId getNextHop() {
            return nextHop;
        }

        public NodeId getOrigin() {
            return origin;
        }

        public ByteBuffer getFinalPayload() {
            return finalPayload == null ? null : finalPayload.asReadOnlyBuffer();
        }

        public StatelessControlEnvelope getForwardedEnvelope() {
            return forwardedEnvelope;
        }
    }
}
