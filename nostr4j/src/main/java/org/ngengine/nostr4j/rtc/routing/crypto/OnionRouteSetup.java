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
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.packet.RouteSetupEnvelope;

/**
 * Builds and peels small source-routed onion setup capsules. Application
 * payloads are never included in these layers.
 */
public final class OnionRouteSetup {

    private static final int LAYER_MAGIC = 0x4443344c;
    private static final byte LAYER_VERSION = 1;
    private static final byte FLAG_FINAL = 1;
    private static final int LAYER_FIXED_BYTES = 4 + 1 + 1 + CircuitId.SIZE + 8 + 1 + 1 + 4 + 8 + NodeId.SIZE + NodeId.SIZE + 4;

    public BuiltSetup build(
        List<NodeId> route,
        Map<NodeId, NostrPublicKey> routingPublicKeys,
        RouteTransportProfile profile,
        Instant expiresAt,
        Instant now
    ) {
        if (route.size() < 2 || route.size() - 1 > RoutingLimits.MAX_ROUTE_HOPS) {
            throw new IllegalArgumentException("Invalid route length");
        }
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Invalid route setup expiry");
        }
        CircuitId setupId = CircuitId.random();
        CircuitId circuitId = CircuitId.random();
        NostrKeyPair ephemeral = new NostrKeyPair();
        NostrPublicKey ephemeralPublicKey = ephemeral.getPublicKey();
        byte[] inner = null;
        try {
            for (int index = route.size() - 1; index >= 1; index--) {
                NodeId hop = route.get(index);
                NostrPublicKey hopKey = routingPublicKeys.get(hop);
                if (hopKey == null) throw new IllegalArgumentException("Missing routing key for route hop");
                boolean destination = index == route.size() - 1;
                int remaining = route.size() - index;
                byte[] layer = encodeLayer(
                    circuitId,
                    expiresAt,
                    remaining,
                    destination,
                    destination ? null : route.get(index + 1),
                    destination ? route.get(0) : null,
                    profile,
                    inner
                );
                byte[] conversationKey = Nip44.getConversationKeySync(ephemeral.getPrivateKey(), hopKey);
                try {
                    inner = Nip44.encryptSyncBinary(layer, conversationKey);
                } finally {
                    Arrays.fill(conversationKey, (byte) 0);
                    Arrays.fill(layer, (byte) 0);
                }
                if (inner.length > RoutingLimits.MAX_ROUTE_SETUP_BYTES) {
                    throw new IllegalArgumentException("Encrypted route setup exceeds size limit");
                }
            }
        } finally {
            ephemeral.destroy();
        }
        RouteSetupEnvelope envelope = new RouteSetupEnvelope(
            setupId,
            circuitId,
            expiresAt,
            route.size() - 1,
            ephemeralPublicKey,
            ByteBuffer.wrap(inner)
        );
        return new BuiltSetup(setupId, circuitId, envelope);
    }

    public PeeledSetup peel(RouteSetupEnvelope envelope, NostrPrivateKey localRoutingKey, Instant now) {
        if (!envelope.getExpiresAt().isAfter(now)) throw new IllegalArgumentException("Route setup is expired");
        if (envelope.getExpiresAt().isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Route setup expiry exceeds limit");
        }
        ByteBuffer encrypted = envelope.getCiphertext();
        byte[] encryptedBytes = new byte[encrypted.remaining()];
        encrypted.get(encryptedBytes);
        byte[] conversationKey = Nip44.getConversationKeySync(localRoutingKey, envelope.getEphemeralPublicKey());
        byte[] plaintext;
        try {
            plaintext = Nip44.decryptSyncBinary(encryptedBytes, conversationKey);
        } finally {
            Arrays.fill(conversationKey, (byte) 0);
            Arrays.fill(encryptedBytes, (byte) 0);
        }
        DecodedLayer layer;
        try {
            layer = decodeLayer(ByteBuffer.wrap(plaintext), envelope);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        RouteSetupEnvelope forwarded = null;
        if (!layer.destination) {
            forwarded =
                new RouteSetupEnvelope(
                    envelope.getSetupId(),
                    envelope.getCircuitId(),
                    envelope.getExpiresAt(),
                    envelope.getRemainingHops() - 1,
                    envelope.getEphemeralPublicKey(),
                    layer.inner
                );
        }
        return new PeeledSetup(
            envelope.getSetupId(),
            envelope.getCircuitId(),
            layer.destination,
            layer.nextHop,
            layer.source,
            layer.profile,
            envelope.getExpiresAt(),
            forwarded
        );
    }

    private static byte[] encodeLayer(
        CircuitId circuitId,
        Instant expiresAt,
        int remaining,
        boolean destination,
        NodeId next,
        NodeId source,
        RouteTransportProfile profile,
        byte[] inner
    ) {
        int innerLength = inner == null ? 0 : inner.length;
        if (LAYER_FIXED_BYTES + innerLength > 0xffff) {
            throw new IllegalArgumentException("Onion setup layer exceeds NIP-44 limit");
        }
        ByteBuffer out = ByteBuffer.allocate(LAYER_FIXED_BYTES + innerLength).order(ByteOrder.BIG_ENDIAN);
        out.putInt(LAYER_MAGIC);
        out.put(LAYER_VERSION);
        out.put(destination ? FLAG_FINAL : (byte) 0);
        out.put(circuitId.toByteArray());
        out.putLong(expiresAt.getEpochSecond());
        out.put((byte) remaining);
        int profileFlags = (profile.isOrdered() ? 1 : 0) | (profile.isReliable() ? 2 : 0);
        out.put((byte) profileFlags);
        out.putInt(profile.getMaxRetransmits() == null ? -1 : profile.getMaxRetransmits().intValue());
        out.putLong(profile.getMaxPacketLifeTime() == null ? -1L : profile.getMaxPacketLifeTime().toMillis());
        out.put(next == null ? new byte[NodeId.SIZE] : next.toByteArray());
        out.put(source == null ? new byte[NodeId.SIZE] : source.toByteArray());
        out.putInt(innerLength);
        if (inner != null) out.put(inner);
        return out.array();
    }

    private static DecodedLayer decodeLayer(ByteBuffer input, RouteSetupEnvelope envelope) {
        ByteBuffer data = input.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        if (data.remaining() < LAYER_FIXED_BYTES) throw new IllegalArgumentException("Truncated onion setup layer");
        if (data.getInt() != LAYER_MAGIC) throw new IllegalArgumentException("Invalid onion setup magic");
        if (data.get() != LAYER_VERSION) throw new IllegalArgumentException("Invalid onion setup version");
        int flags = data.get() & 0xff;
        if ((flags & ~FLAG_FINAL) != 0) throw new IllegalArgumentException("Invalid onion setup flags");
        CircuitId circuit = CircuitId.read(data);
        if (!circuit.equals(envelope.getCircuitId())) throw new IllegalArgumentException("Circuit id mismatch");
        if (data.getLong() != envelope.getExpiresAt().getEpochSecond()) {
            throw new IllegalArgumentException("Route setup expiry mismatch");
        }
        int remaining = data.get() & 0xff;
        if (remaining != envelope.getRemainingHops()) throw new IllegalArgumentException("Route setup hop mismatch");
        int profileFlags = data.get() & 0xff;
        if ((profileFlags & ~3) != 0) throw new IllegalArgumentException("Invalid route transport profile flags");
        int maxRetransmits = data.getInt();
        long maxLifetimeMillis = data.getLong();
        RouteTransportProfile profile = decodeProfile(profileFlags, maxRetransmits, maxLifetimeMillis);
        byte[] nextBytes = new byte[NodeId.SIZE];
        byte[] sourceBytes = new byte[NodeId.SIZE];
        data.get(nextBytes);
        data.get(sourceBytes);
        int innerLength = data.getInt();
        if (innerLength < 0 || innerLength != data.remaining()) {
            throw new IllegalArgumentException("Invalid inner onion setup length");
        }
        boolean destination = (flags & FLAG_FINAL) != 0;
        boolean nextZero = allZero(nextBytes);
        boolean sourceZero = allZero(sourceBytes);
        if (destination) {
            if (remaining != 1 || !nextZero || sourceZero || innerLength != 0) {
                throw new IllegalArgumentException("Invalid final onion setup layer");
            }
        } else if (remaining <= 1 || nextZero || !sourceZero || innerLength == 0) {
            throw new IllegalArgumentException("Invalid forwarding onion setup layer");
        }
        byte[] innerBytes = new byte[data.remaining()];
        data.get(innerBytes);
        ByteBuffer inner = ByteBuffer.wrap(innerBytes).asReadOnlyBuffer();
        return new DecodedLayer(
            destination,
            nextZero ? null : NodeId.fromHex(org.ngengine.platform.NGEUtils.bytesToHex(nextBytes)),
            sourceZero ? null : NodeId.fromHex(org.ngengine.platform.NGEUtils.bytesToHex(sourceBytes)),
            profile,
            inner
        );
    }

    private static RouteTransportProfile decodeProfile(int flags, int retransmits, long lifetimeMillis) {
        if (retransmits < -1 || lifetimeMillis < -1 || (retransmits >= 0 && lifetimeMillis >= 0)) {
            throw new IllegalArgumentException("Invalid partial reliability profile");
        }
        return new RouteTransportProfile(
            (flags & 1) != 0,
            (flags & 2) != 0,
            retransmits < 0 ? null : Integer.valueOf(retransmits),
            lifetimeMillis < 0 ? null : java.time.Duration.ofMillis(lifetimeMillis)
        );
    }

    private static boolean allZero(byte[] bytes) {
        int value = 0;
        for (byte current : bytes) value |= current;
        return value == 0;
    }

    public static final class BuiltSetup {

        private final CircuitId setupId;
        private final CircuitId circuitId;
        private final RouteSetupEnvelope envelope;

        private BuiltSetup(CircuitId setupId, CircuitId circuitId, RouteSetupEnvelope envelope) {
            this.setupId = setupId;
            this.circuitId = circuitId;
            this.envelope = envelope;
        }

        public CircuitId getSetupId() {
            return setupId;
        }

        public CircuitId getCircuitId() {
            return circuitId;
        }

        public RouteSetupEnvelope getEnvelope() {
            return envelope;
        }
    }

    public static final class PeeledSetup {

        private final CircuitId setupId;
        private final CircuitId circuitId;
        private final boolean destination;
        private final NodeId nextHop;
        private final NodeId source;
        private final RouteTransportProfile profile;
        private final Instant expiresAt;
        private final RouteSetupEnvelope forwardedEnvelope;

        private PeeledSetup(
            CircuitId setupId,
            CircuitId circuitId,
            boolean destination,
            NodeId nextHop,
            NodeId source,
            RouteTransportProfile profile,
            Instant expiresAt,
            RouteSetupEnvelope forwardedEnvelope
        ) {
            this.setupId = setupId;
            this.circuitId = circuitId;
            this.destination = destination;
            this.nextHop = nextHop;
            this.source = source;
            this.profile = profile;
            this.expiresAt = expiresAt;
            this.forwardedEnvelope = forwardedEnvelope;
        }

        public CircuitId getSetupId() {
            return setupId;
        }

        public CircuitId getCircuitId() {
            return circuitId;
        }

        public boolean isDestination() {
            return destination;
        }

        public NodeId getNextHop() {
            return nextHop;
        }

        public NodeId getSource() {
            return source;
        }

        public RouteTransportProfile getProfile() {
            return profile;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public RouteSetupEnvelope getForwardedEnvelope() {
            return forwardedEnvelope;
        }
    }

    private static final class DecodedLayer {

        private final boolean destination;
        private final NodeId nextHop;
        private final NodeId source;
        private final RouteTransportProfile profile;
        private final ByteBuffer inner;

        private DecodedLayer(
            boolean destination,
            NodeId nextHop,
            NodeId source,
            RouteTransportProfile profile,
            ByteBuffer inner
        ) {
            this.destination = destination;
            this.nextHop = nextHop;
            this.source = source;
            this.profile = profile;
            this.inner = inner;
        }
    }
}
