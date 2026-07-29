/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.crypto;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutingWire;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * Route-independent end-to-end encryption for a normal NIP-DC fragment.
 */
public final class RoutePayloadCrypto implements Closeable {

    public static final int ACK_TOKEN_BYTES = 16;
    private static final int MAGIC = 0x44433450;
    private static final byte VERSION = 1;
    private static final int FIXED_PLAINTEXT_BYTES = 4 + 1 + 1 + NodeId.SIZE * 2 + 2 + 8 + 2 + 2 + ACK_TOKEN_BYTES + 4;
    private static final int NIP44_MAX_PLAINTEXT = 0xffff;
    private static final int NORMAL_FRAME_HEADER_BYTES = 8 + 2 + 2;
    private static final int MAX_CHANNEL_BYTES = 1024;

    private final NostrKeyPair localRoutingKeys;
    private final RoutingConversationKeyCache conversations = new RoutingConversationKeyCache();
    private final AtomicLong encryptionCount = new AtomicLong();

    public RoutePayloadCrypto(NostrKeyPair localRoutingKeys) {
        this.localRoutingKeys = Objects.requireNonNull(localRoutingKeys);
    }

    public int maximumNormalFrameBytes(String channel) {
        if (InternalRoutingChannels.isReserved(channel)) {
            throw new IllegalArgumentException("Routed channel is reserved for internal routing");
        }
        int channelBytes = RoutingWire.encodeUtf8(channel, "routed channel").length;
        if (channelBytes < 1 || channelBytes > MAX_CHANNEL_BYTES) {
            throw new IllegalArgumentException("Invalid routed channel label length");
        }
        return NIP44_MAX_PLAINTEXT - FIXED_PLAINTEXT_BYTES - channelBytes;
    }

    public EncryptedPayload encrypt(
        NodeId source,
        NodeId destination,
        NostrPublicKey destinationRoutingPublicKey,
        String logicalChannel,
        RouteTransportProfile profile,
        ByteBuffer normalFrame,
        byte[] acknowledgementToken
    ) {
        byte[] channel = RoutingWire.encodeUtf8(logicalChannel, "routed channel");
        if (channel.length < 1 || channel.length > MAX_CHANNEL_BYTES) {
            throw new IllegalArgumentException("Invalid routed channel label length");
        }
        if (InternalRoutingChannels.isReserved(logicalChannel)) {
            throw new IllegalArgumentException("Routed channel is reserved for internal routing");
        }
        ByteBuffer frame = normalFrame.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        FragmentIdentity identity = fragmentIdentity(frame);
        if (frame.remaining() > maximumNormalFrameBytes(logicalChannel)) {
            throw new IllegalArgumentException("Normal NIP-DC frame exceeds routed crypto limit");
        }
        boolean ackRequired = profile.requiresDestinationAck();
        byte[] ackToken = validateAckToken(acknowledgementToken, ackRequired);
        int flags = (ackRequired ? 1 : 0) | (profile.isOrdered() ? 2 : 0) | (profile.isReliable() ? 4 : 0);
        ByteBuffer plaintext = ByteBuffer
            .allocate(FIXED_PLAINTEXT_BYTES + channel.length + frame.remaining())
            .order(ByteOrder.BIG_ENDIAN);
        plaintext.putInt(MAGIC);
        plaintext.put(VERSION);
        plaintext.put((byte) flags);
        plaintext.put(source.toByteArray());
        plaintext.put(destination.toByteArray());
        plaintext.putShort((short) channel.length);
        plaintext.put(channel);
        plaintext.putLong(identity.packetId);
        plaintext.putShort((short) identity.fragmentId);
        plaintext.putShort((short) identity.fragmentCount);
        plaintext.put(ackToken);
        plaintext.putInt(frame.remaining());
        plaintext.put(frame);
        byte[] key = conversations.get(localRoutingKeys.getPrivateKey(), destination, destinationRoutingPublicKey);
        try {
            byte[] ciphertext = Nip44.encryptSyncBinary(plaintext.array(), key);
            encryptionCount.incrementAndGet();
            return new EncryptedPayload(
                source,
                destination,
                logicalChannel,
                identity.packetId,
                identity.fragmentId,
                identity.fragmentCount,
                ackRequired,
                ackToken,
                ByteBuffer.wrap(ciphertext).asReadOnlyBuffer()
            );
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public DecryptedPayload decrypt(
        NodeId expectedSource,
        NodeId localDestination,
        NostrPublicKey sourceRoutingPublicKey,
        ByteBuffer ciphertext
    ) {
        ByteBuffer encrypted = ciphertext.asReadOnlyBuffer();
        byte[] encryptedBytes = new byte[encrypted.remaining()];
        encrypted.get(encryptedBytes);
        byte[] key = conversations.get(localRoutingKeys.getPrivateKey(), expectedSource, sourceRoutingPublicKey);
        final byte[] plaintext;
        try {
            plaintext = Nip44.decryptSyncBinary(encryptedBytes, key);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(encryptedBytes, (byte) 0);
        }
        try {
            ByteBuffer data = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN);
            if (data.remaining() < FIXED_PLAINTEXT_BYTES + 1 + NORMAL_FRAME_HEADER_BYTES) {
                throw new IllegalArgumentException("Truncated routed payload");
            }
            if (data.getInt() != MAGIC) throw new IllegalArgumentException("Invalid routed payload magic");
            if (data.get() != VERSION) throw new IllegalArgumentException("Invalid routed payload version");
            int flags = data.get() & 0xff;
            if ((flags & ~7) != 0) throw new IllegalArgumentException("Invalid routed payload flags");
            NodeId source = readNode(data);
            NodeId destination = readNode(data);
            if (!source.equals(expectedSource) || !destination.equals(localDestination)) {
                throw new SecurityException("Routed payload endpoint mismatch");
            }
            int channelLength = data.getShort() & 0xffff;
            int trailingFixedBytes = 8 + 2 + 2 + ACK_TOKEN_BYTES + 4 + NORMAL_FRAME_HEADER_BYTES;
            if (
                channelLength < 1 || channelLength > MAX_CHANNEL_BYTES || channelLength > data.remaining() - trailingFixedBytes
            ) {
                throw new IllegalArgumentException("Invalid routed channel length");
            }
            byte[] channelBytes = new byte[channelLength];
            data.get(channelBytes);
            String channel = RoutingWire.decodeUtf8(channelBytes, "routed channel");
            if (InternalRoutingChannels.isReserved(channel)) {
                throw new SecurityException("Routed channel is reserved for internal routing");
            }
            long packetId = data.getLong();
            int fragmentId = data.getShort();
            int fragmentCount = data.getShort();
            byte[] ackToken = new byte[ACK_TOKEN_BYTES];
            data.get(ackToken);
            int frameLength = data.getInt();
            if (frameLength < NORMAL_FRAME_HEADER_BYTES || frameLength != data.remaining()) {
                throw new IllegalArgumentException("Invalid routed normal frame length");
            }
            byte[] frameBytes = new byte[frameLength];
            data.get(frameBytes);
            ByteBuffer frame = ByteBuffer.wrap(frameBytes).asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            FragmentIdentity inner = fragmentIdentity(frame);
            if (packetId != inner.packetId || fragmentId != inner.fragmentId || fragmentCount != inner.fragmentCount) {
                Arrays.fill(frameBytes, (byte) 0);
                throw new SecurityException("Routed fragment identity mismatch");
            }
            boolean ackRequired = (flags & 1) != 0;
            if (ackRequired == NGEUtils.allZeroes(ackToken)) {
                Arrays.fill(frameBytes, (byte) 0);
                throw new SecurityException("Invalid routed acknowledgement token");
            }
            return new DecryptedPayload(
                source,
                destination,
                channel,
                packetId,
                fragmentId,
                fragmentCount,
                ackRequired,
                (flags & 2) != 0,
                (flags & 4) != 0,
                ackToken,
                frame
            );
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public long getEncryptionCount() {
        return encryptionCount.get();
    }

    int getCachedConversationCount() {
        return conversations.size();
    }

    private static byte[] validateAckToken(byte[] token, boolean required) {
        if (!required) return new byte[ACK_TOKEN_BYTES];
        byte[] value = token == null ? NGEPlatform.get().randomBytes(ACK_TOKEN_BYTES) : token.clone();
        if (value.length != ACK_TOKEN_BYTES || NGEUtils.allZeroes(value)) {
            throw new IllegalArgumentException("Acknowledgement token must be 16 non-zero bytes");
        }
        return value;
    }

    private static FragmentIdentity fragmentIdentity(ByteBuffer frame) {
        ByteBuffer data = frame.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        if (data.remaining() < NORMAL_FRAME_HEADER_BYTES) {
            throw new IllegalArgumentException("Truncated normal NIP-DC frame");
        }
        long packetId = data.getLong();
        int fragmentId = data.getShort();
        int fragmentCount = data.getShort();
        if (packetId <= 0L || fragmentCount <= 0 || fragmentId < 0 || fragmentId >= fragmentCount) {
            throw new IllegalArgumentException("Invalid normal NIP-DC fragment identity");
        }
        return new FragmentIdentity(packetId, fragmentId, fragmentCount);
    }

    private static NodeId readNode(ByteBuffer data) {
        byte[] node = new byte[NodeId.SIZE];
        data.get(node);
        return NodeId.fromHex(NGEUtils.bytesToHex(node));
    }

    @Override
    public void close() {
        conversations.close();
    }

    public static final class EncryptedPayload {

        private final NodeId source;
        private final NodeId destination;
        private final String logicalChannel;
        private final long packetId;
        private final int fragmentId;
        private final int fragmentCount;
        private final boolean acknowledgementRequired;
        private final byte[] acknowledgementToken;
        private final ByteBuffer ciphertext;

        private EncryptedPayload(
            NodeId source,
            NodeId destination,
            String logicalChannel,
            long packetId,
            int fragmentId,
            int fragmentCount,
            boolean acknowledgementRequired,
            byte[] acknowledgementToken,
            ByteBuffer ciphertext
        ) {
            this.source = source;
            this.destination = destination;
            this.logicalChannel = logicalChannel;
            this.packetId = packetId;
            this.fragmentId = fragmentId;
            this.fragmentCount = fragmentCount;
            this.acknowledgementRequired = acknowledgementRequired;
            this.acknowledgementToken = acknowledgementToken.clone();
            this.ciphertext = ciphertext.asReadOnlyBuffer();
        }

        public NodeId getSource() {
            return source;
        }

        public NodeId getDestination() {
            return destination;
        }

        public String getLogicalChannel() {
            return logicalChannel;
        }

        public long getPacketId() {
            return packetId;
        }

        public int getFragmentId() {
            return fragmentId;
        }

        public int getFragmentCount() {
            return fragmentCount;
        }

        public boolean isAcknowledgementRequired() {
            return acknowledgementRequired;
        }

        public byte[] getAcknowledgementToken() {
            return acknowledgementToken.clone();
        }

        public ByteBuffer getCiphertext() {
            return ciphertext.asReadOnlyBuffer();
        }
    }

    public static final class DecryptedPayload {

        private final NodeId source;
        private final NodeId destination;
        private final String logicalChannel;
        private final long packetId;
        private final int fragmentId;
        private final int fragmentCount;
        private final boolean acknowledgementRequired;
        private final boolean ordered;
        private final boolean reliable;
        private final byte[] acknowledgementToken;
        private final ByteBuffer normalFrame;

        private DecryptedPayload(
            NodeId source,
            NodeId destination,
            String logicalChannel,
            long packetId,
            int fragmentId,
            int fragmentCount,
            boolean acknowledgementRequired,
            boolean ordered,
            boolean reliable,
            byte[] acknowledgementToken,
            ByteBuffer normalFrame
        ) {
            this.source = source;
            this.destination = destination;
            this.logicalChannel = logicalChannel;
            this.packetId = packetId;
            this.fragmentId = fragmentId;
            this.fragmentCount = fragmentCount;
            this.acknowledgementRequired = acknowledgementRequired;
            this.ordered = ordered;
            this.reliable = reliable;
            this.acknowledgementToken = acknowledgementToken.clone();
            this.normalFrame = normalFrame.asReadOnlyBuffer();
        }

        public NodeId getSource() {
            return source;
        }

        public NodeId getDestination() {
            return destination;
        }

        public String getLogicalChannel() {
            return logicalChannel;
        }

        public long getPacketId() {
            return packetId;
        }

        public int getFragmentId() {
            return fragmentId;
        }

        public int getFragmentCount() {
            return fragmentCount;
        }

        public boolean isAcknowledgementRequired() {
            return acknowledgementRequired;
        }

        public boolean isOrdered() {
            return ordered;
        }

        public boolean isReliable() {
            return reliable;
        }

        public byte[] getAcknowledgementToken() {
            return acknowledgementToken.clone();
        }

        public ByteBuffer getNormalFrame() {
            return normalFrame.asReadOnlyBuffer();
        }
    }

    private static final class FragmentIdentity {

        private final long packetId;
        private final int fragmentId;
        private final int fragmentCount;

        private FragmentIdentity(long packetId, int fragmentId, int fragmentCount) {
            this.packetId = packetId;
            this.fragmentId = fragmentId;
            this.fragmentCount = fragmentCount;
        }
    }
}
