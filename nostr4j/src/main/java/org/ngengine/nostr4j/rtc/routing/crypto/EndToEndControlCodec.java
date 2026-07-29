/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingWire;
import org.ngengine.nostr4j.rtc.routing.packet.EndToEndControlType;
import org.ngengine.platform.NGEUtils;

public final class EndToEndControlCodec {

    private static final int MAGIC = 0x44433445;
    private static final byte VERSION = 1;
    private static final int TOKEN_BYTES = 16;
    private static final int FIXED_BYTES = 4 + 1 + 1 + NodeId.SIZE * 2 + CircuitId.SIZE * 2 + 8 + 2 + 2 + TOKEN_BYTES;
    private static final int MAX_CHANNEL_BYTES = 1024;

    public ByteBuffer encrypt(Message message, NostrPrivateKey senderPrivateKey, NostrPublicKey destinationPublicKey) {
        byte[] channel = RoutingWire.encodeUtf8(message.logicalChannel, "control channel");
        if (channel.length > MAX_CHANNEL_BYTES) throw new IllegalArgumentException("Control channel label too long");
        ByteBuffer plaintext = ByteBuffer.allocate(FIXED_BYTES + channel.length).order(ByteOrder.BIG_ENDIAN);
        plaintext.putInt(MAGIC);
        plaintext.put(VERSION);
        plaintext.put((byte) message.type.wireValue());
        plaintext.put(message.sender.toByteArray());
        plaintext.put(message.destination.toByteArray());
        plaintext.put(message.correlationId.toByteArray());
        plaintext.put(message.circuitId.toByteArray());
        plaintext.putLong(message.packetId);
        plaintext.putShort((short) message.fragmentId);
        plaintext.putShort((short) channel.length);
        plaintext.put(message.token);
        plaintext.put(channel);
        byte[] key = Nip44.getConversationKeySync(senderPrivateKey, destinationPublicKey);
        try {
            return ByteBuffer.wrap(Nip44.encryptSyncBinary(plaintext.array(), key)).asReadOnlyBuffer();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public Message decrypt(
        ByteBuffer ciphertext,
        NodeId expectedSender,
        NodeId localDestination,
        NostrPrivateKey localPrivateKey,
        NostrPublicKey senderPublicKey
    ) {
        ByteBuffer encrypted = ciphertext.asReadOnlyBuffer();
        byte[] bytes = new byte[encrypted.remaining()];
        encrypted.get(bytes);
        byte[] key = Nip44.getConversationKeySync(localPrivateKey, senderPublicKey);
        final byte[] plaintext;
        try {
            plaintext = Nip44.decryptSyncBinary(bytes, key);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(bytes, (byte) 0);
        }
        try {
            ByteBuffer data = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN);
            if (data.remaining() < FIXED_BYTES) throw new IllegalArgumentException("Truncated end-to-end control");
            if (data.getInt() != MAGIC) throw new IllegalArgumentException("Invalid end-to-end control magic");
            if (data.get() != VERSION) throw new IllegalArgumentException("Invalid end-to-end control version");
            EndToEndControlType type = EndToEndControlType.fromWire(data.get() & 0xff);
            NodeId sender = readNode(data);
            NodeId destination = readNode(data);
            if (!sender.equals(expectedSender) || !destination.equals(localDestination)) {
                throw new SecurityException("End-to-end control endpoint mismatch");
            }
            CircuitId correlation = CircuitId.read(data);
            CircuitId circuit = CircuitId.read(data);
            long packetId = data.getLong();
            int fragmentId = data.getShort();
            int channelLength = data.getShort() & 0xffff;
            byte[] token = new byte[TOKEN_BYTES];
            data.get(token);
            if (channelLength > MAX_CHANNEL_BYTES || channelLength != data.remaining()) {
                throw new IllegalArgumentException("Invalid end-to-end control channel length");
            }
            byte[] channel = new byte[channelLength];
            data.get(channel);
            return new Message(
                type,
                sender,
                destination,
                correlation,
                circuit,
                packetId,
                fragmentId,
                RoutingWire.decodeUtf8(channel, "control channel"),
                token
            );
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static NodeId readNode(ByteBuffer input) {
        byte[] bytes = new byte[NodeId.SIZE];
        input.get(bytes);
        return NodeId.fromHex(NGEUtils.bytesToHex(bytes));
    }

    public static final class Message {

        private final EndToEndControlType type;
        private final NodeId sender;
        private final NodeId destination;
        private final CircuitId correlationId;
        private final CircuitId circuitId;
        private final long packetId;
        private final int fragmentId;
        private final String logicalChannel;
        private final byte[] token;

        public Message(
            EndToEndControlType type,
            NodeId sender,
            NodeId destination,
            CircuitId correlationId,
            CircuitId circuitId,
            long packetId,
            int fragmentId,
            String logicalChannel,
            byte[] token
        ) {
            if (token == null || token.length != TOKEN_BYTES) {
                throw new IllegalArgumentException("End-to-end control token must contain 16 bytes");
            }
            this.type = type;
            this.sender = sender;
            this.destination = destination;
            this.correlationId = correlationId;
            this.circuitId = circuitId;
            this.packetId = packetId;
            this.fragmentId = fragmentId;
            this.logicalChannel = logicalChannel == null ? "" : logicalChannel;
            this.token = token.clone();
        }

        public EndToEndControlType getType() {
            return type;
        }

        public NodeId getSender() {
            return sender;
        }

        public NodeId getDestination() {
            return destination;
        }

        public CircuitId getCorrelationId() {
            return correlationId;
        }

        public CircuitId getCircuitId() {
            return circuitId;
        }

        public long getPacketId() {
            return packetId;
        }

        public int getFragmentId() {
            return fragmentId;
        }

        public String getLogicalChannel() {
            return logicalChannel;
        }

        public byte[] getToken() {
            return token.clone();
        }
    }
}
