/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.RoutingWire;
import org.ngengine.platform.NGEUtils;

public final class BroadcastFrame {

    private static final int MAGIC = 0x44433442;
    private static final byte VERSION = 1;
    private static final int SNAPSHOT_ID_BYTES = 32;
    private static final int FIXED_BYTES =
        4 + 1 + 1 + 1 + 1 + 4 + 8 + 8 + NodeId.SIZE + CircuitId.SIZE + SNAPSHOT_ID_BYTES + 2 + 4;
    private static final int MAX_CHANNEL_BYTES = 1024;

    private final NodeId origin;
    private final CircuitId broadcastId;
    private final String graphSnapshotId;
    private final String logicalChannel;
    private final boolean reliable;
    private final boolean ordered;
    private final Integer maxRetransmits;
    private final Duration maxPacketLifeTime;
    private final int hopLimit;
    private final Instant expiresAt;
    private final ByteBuffer payload;
    private final ByteBuffer wireView;

    public BroadcastFrame(
        NodeId origin,
        CircuitId broadcastId,
        String graphSnapshotId,
        String logicalChannel,
        boolean reliable,
        boolean ordered,
        int hopLimit,
        Instant expiresAt,
        ByteBuffer payload
    ) {
        this(
            origin,
            broadcastId,
            graphSnapshotId,
            logicalChannel,
            reliable,
            ordered,
            null,
            null,
            hopLimit,
            expiresAt,
            payload,
            null
        );
    }

    public BroadcastFrame(
        NodeId origin,
        CircuitId broadcastId,
        String graphSnapshotId,
        String logicalChannel,
        RouteTransportProfile profile,
        int hopLimit,
        Instant expiresAt,
        ByteBuffer payload
    ) {
        this(
            origin,
            broadcastId,
            graphSnapshotId,
            logicalChannel,
            profile.isReliable(),
            profile.isOrdered(),
            profile.getMaxRetransmits(),
            profile.getMaxPacketLifeTime(),
            hopLimit,
            expiresAt,
            payload,
            null
        );
    }

    private BroadcastFrame(
        NodeId origin,
        CircuitId broadcastId,
        String graphSnapshotId,
        String logicalChannel,
        boolean reliable,
        boolean ordered,
        Integer maxRetransmits,
        Duration maxPacketLifeTime,
        int hopLimit,
        Instant expiresAt,
        ByteBuffer payload,
        ByteBuffer wireView
    ) {
        byte[] channel = RoutingWire.encodeUtf8(logicalChannel, "broadcast channel");
        if (channel.length < 1 || channel.length > MAX_CHANNEL_BYTES) {
            throw new IllegalArgumentException("Invalid broadcast channel length");
        }
        if (InternalRoutingChannels.isReserved(logicalChannel)) {
            throw new IllegalArgumentException("Broadcast channel is reserved for internal routing");
        }
        if (hopLimit < 1 || hopLimit > RoutingLimits.MAX_ROUTE_HOPS) {
            throw new IllegalArgumentException("Invalid broadcast hop limit");
        }
        if (payload == null || !payload.hasRemaining()) throw new IllegalArgumentException("Empty broadcast payload");
        if (FIXED_BYTES + channel.length + payload.remaining() > RoutingLimits.MAX_ROUTED_FRAME_BYTES) {
            throw new IllegalArgumentException("Broadcast frame exceeds size limit");
        }
        if (NGEUtils.hexToBytes(graphSnapshotId).remaining() != SNAPSHOT_ID_BYTES) {
            throw new IllegalArgumentException("Invalid broadcast graph snapshot id");
        }
        this.origin = Objects.requireNonNull(origin);
        this.broadcastId = Objects.requireNonNull(broadcastId);
        this.graphSnapshotId = graphSnapshotId;
        this.logicalChannel = logicalChannel;
        this.reliable = reliable;
        this.ordered = ordered;
        this.maxRetransmits = maxRetransmits;
        this.maxPacketLifeTime = maxPacketLifeTime;
        this.hopLimit = hopLimit;
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.payload = payload.asReadOnlyBuffer();
        this.wireView = wireView == null ? null : wireView.asReadOnlyBuffer();
    }

    public NodeId getOrigin() {
        return origin;
    }

    public CircuitId getBroadcastId() {
        return broadcastId;
    }

    public String getGraphSnapshotId() {
        return graphSnapshotId;
    }

    public String getLogicalChannel() {
        return logicalChannel;
    }

    public boolean isReliable() {
        return reliable;
    }

    public boolean isOrdered() {
        return ordered;
    }

    public RouteTransportProfile getProfile() {
        return new RouteTransportProfile(ordered, reliable, maxRetransmits, maxPacketLifeTime);
    }

    public int getHopLimit() {
        return hopLimit;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ByteBuffer getPayload() {
        return payload.asReadOnlyBuffer();
    }

    public ByteBuffer forwardingView() {
        return wireView == null ? encode() : wireView.asReadOnlyBuffer();
    }

    public ByteBuffer encode() {
        byte[] channel = RoutingWire.encodeUtf8(logicalChannel, "broadcast channel");
        ByteBuffer content = payload.asReadOnlyBuffer();
        ByteBuffer out = ByteBuffer.allocate(FIXED_BYTES + channel.length + content.remaining()).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put(VERSION);
        int flags = (reliable ? 1 : 0) | (ordered ? 2 : 0);
        out.put((byte) flags);
        out.put((byte) hopLimit);
        out.put((byte) 0);
        out.putInt(maxRetransmits == null ? -1 : maxRetransmits.intValue());
        out.putLong(maxPacketLifeTime == null ? -1L : maxPacketLifeTime.toMillis());
        out.putLong(expiresAt.getEpochSecond());
        out.put(origin.toByteArray());
        out.put(broadcastId.toByteArray());
        out.put(NGEUtils.hexToBytes(graphSnapshotId));
        out.putShort((short) channel.length);
        out.putInt(content.remaining());
        out.put(channel);
        out.put(content);
        out.flip();
        return out.asReadOnlyBuffer();
    }

    public static BroadcastFrame decode(ByteBuffer input, Instant now) {
        ByteBuffer full = input.slice().order(ByteOrder.BIG_ENDIAN);
        if (full.remaining() < FIXED_BYTES || full.remaining() > RoutingLimits.MAX_ROUTED_FRAME_BYTES) {
            throw new IllegalArgumentException("Invalid broadcast frame length");
        }
        ByteBuffer data = full.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (data.getInt() != MAGIC) throw new IllegalArgumentException("Invalid broadcast magic");
        if (data.get() != VERSION) throw new IllegalArgumentException("Invalid broadcast version");
        int flags = data.get() & 0xff;
        if ((flags & ~3) != 0) throw new IllegalArgumentException("Invalid broadcast flags");
        int hopLimit = data.get() & 0xff;
        if (data.get() != 0) throw new IllegalArgumentException("Invalid broadcast reserved field");
        int maxRetransmits = data.getInt();
        long maxLifetimeMillis = data.getLong();
        if (maxRetransmits < -1 || maxLifetimeMillis < -1 || (maxRetransmits >= 0 && maxLifetimeMillis >= 0)) {
            throw new IllegalArgumentException("Invalid broadcast reliability limits");
        }
        Instant expiry = Instant.ofEpochSecond(data.getLong());
        if (!expiry.isAfter(now)) throw new IllegalArgumentException("Broadcast frame is expired");
        if (expiry.isAfter(now.plusSeconds(RoutingLimits.MAX_CIRCUIT_LIFETIME_SECONDS))) {
            throw new IllegalArgumentException("Broadcast frame expiry exceeds limit");
        }
        byte[] origin = new byte[NodeId.SIZE];
        data.get(origin);
        CircuitId id = CircuitId.read(data);
        byte[] snapshot = new byte[SNAPSHOT_ID_BYTES];
        data.get(snapshot);
        int channelLength = data.getShort() & 0xffff;
        int payloadLength = data.getInt();
        if (
            channelLength < 1 ||
            channelLength > MAX_CHANNEL_BYTES ||
            payloadLength < 1 ||
            channelLength + payloadLength != data.remaining()
        ) {
            throw new IllegalArgumentException("Invalid broadcast variable lengths");
        }
        byte[] channel = new byte[channelLength];
        data.get(channel);
        ByteBuffer payload = data.slice().asReadOnlyBuffer();
        return new BroadcastFrame(
            NodeId.fromHex(NGEUtils.bytesToHex(origin)),
            id,
            NGEUtils.bytesToHex(snapshot),
            RoutingWire.decodeUtf8(channel, "broadcast channel"),
            (flags & 1) != 0,
            (flags & 2) != 0,
            maxRetransmits < 0 ? null : Integer.valueOf(maxRetransmits),
            maxLifetimeMillis < 0 ? null : Duration.ofMillis(maxLifetimeMillis),
            hopLimit,
            expiry,
            payload,
            full
        );
    }
}
