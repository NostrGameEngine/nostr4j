/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.nostr4j.turn.ref;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.BlockingPacketQueue;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
import org.ngengine.platform.AsyncTask;

/**
 * Represents one accepted virtual socket on a websocket session.
 *
 * Identity is the tuple from NIP-DC plus a client-generated vsocketId.
 *
 * <p>Important protocol note: the server does not invent the virtual socket id.
 * The client proposes it in the `connect` event and the server validates that:
 * - envelope VSOCKET_ID is non-zero
 * - content.vsocketId is non-zero
 * - content.vsocketId matches envelope VSOCKET_ID
 * - VSOCKET_ID does not collide within the same websocket connection
 *
 * <p>This class stores the accepted routing identity for a single direction:
 * sender(clientPubkey/session) -> target(targetPubkey/session).
 * Forwarding to the opposite direction requires finding a reciprocal socket.
 */
final class TurnVirtualSocket implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(TurnVirtualSocket.class.getName());
    private static final int PRIORITY_RESERVED_SLOTS = 1;

    static final class LogicalIdentity {

        private final String room;
        private final String localPubkey;
        private final String localSessionId;
        private final String remotePubkey;
        private final String remoteSessionId;
        private final String protocolId;
        private final String applicationId;
        private final String channelLabel;

        private LogicalIdentity(
            NostrPublicKey roomPubkey,
            NostrPublicKey localPubkey,
            String localSessionId,
            NostrPublicKey remotePubkey,
            String remoteSessionId,
            String protocolId,
            String applicationId,
            String channelLabel
        ) {
            this.room = roomPubkey.asHex();
            this.localPubkey = localPubkey.asHex();
            this.localSessionId = localSessionId;
            this.remotePubkey = remotePubkey.asHex();
            this.remoteSessionId = remoteSessionId;
            this.protocolId = protocolId;
            this.applicationId = applicationId;
            this.channelLabel = channelLabel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                room,
                localPubkey,
                localSessionId,
                remotePubkey,
                remoteSessionId,
                protocolId,
                applicationId,
                channelLabel
            );
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof LogicalIdentity)) {
                return false;
            }
            LogicalIdentity other = (LogicalIdentity) obj;
            return (
                Objects.equals(room, other.room) &&
                Objects.equals(localPubkey, other.localPubkey) &&
                Objects.equals(localSessionId, other.localSessionId) &&
                Objects.equals(remotePubkey, other.remotePubkey) &&
                Objects.equals(remoteSessionId, other.remoteSessionId) &&
                Objects.equals(protocolId, other.protocolId) &&
                Objects.equals(applicationId, other.applicationId) &&
                Objects.equals(channelLabel, other.channelLabel)
            );
        }
    }

    static LogicalIdentity logicalIdentity(
        NostrPublicKey roomPubkey,
        NostrPublicKey localPubkey,
        String localSessionId,
        NostrPublicKey remotePubkey,
        String remoteSessionId,
        String protocolId,
        String applicationId,
        String channelLabel
    ) {
        return new LogicalIdentity(
            roomPubkey,
            localPubkey,
            localSessionId,
            remotePubkey,
            remoteSessionId,
            protocolId,
            applicationId,
            channelLabel
        );
    }

    // Client-generated and server-accepted virtual socket id for this websocket session.
    private final long vsocketId;
    // Room boundary for routing isolation.
    private final NostrPublicKey roomPubkey;
    // Authenticated sender pubkey from the signed TURN event header.
    private final NostrPublicKey clientPubkey;
    // Destination pubkey declared in the connect routing tag.
    private final NostrPublicKey targetPubkey;
    // Sender session id (`d` tag).
    private final String sourceSessionId;
    // Target session id (`p` tag third value).
    private final String targetSessionId;
    // Protocol/application dimensions (`i` / `y`) for namespace isolation.
    private final String protocolId;
    private final String applicationId;
    // Channel label (`p` tag second value) for per-channel multiplexing.
    private final String channelLabel;
    private final LogicalIdentity logicalIdentity;
    private final BlockingPacketQueue<QueuedOutgoingFrame> queuedPriorityOutgoingFrames;
    private final BlockingPacketQueue<QueuedOutgoingFrame> queuedOutgoingFrames;
    private volatile boolean ackSent = false;

    static final class QueuedOutgoingFrame {

        private final byte[] frameBytes;

        private QueuedOutgoingFrame(byte[] frameBytes) {
            this.frameBytes = frameBytes;
        }

        byte[] getFrameBytes() {
            return frameBytes;
        }
    }

    TurnVirtualSocket(
        long vsocketId,
        NostrPublicKey roomPubkey,
        NostrPublicKey clientPubkey,
        NostrPublicKey targetPubkey,
        String sourceSessionId,
        String targetSessionId,
        String protocolId,
        String applicationId,
        String channelLabel,
        BiFunction<TurnVirtualSocket, QueuedOutgoingFrame, AsyncTask<Boolean>> processQueuedFrame,
        Function<TurnVirtualSocket, Boolean> hasReachableRecipient
    ) {
        this.vsocketId = vsocketId;
        this.roomPubkey = roomPubkey;
        this.clientPubkey = clientPubkey;
        this.targetPubkey = targetPubkey;
        this.sourceSessionId = sourceSessionId;
        this.targetSessionId = targetSessionId;
        this.protocolId = protocolId;
        this.applicationId = applicationId;
        this.channelLabel = channelLabel;
        this.logicalIdentity =
            logicalIdentity(
                roomPubkey,
                clientPubkey,
                sourceSessionId,
                targetPubkey,
                targetSessionId,
                protocolId,
                applicationId,
                channelLabel
            );
        this.queuedPriorityOutgoingFrames =
            new BlockingPacketQueue<QueuedOutgoingFrame>(
                new BlockingPacketQueue.PacketHandler<TurnVirtualSocket.QueuedOutgoingFrame>() {
                    @Override
                    public AsyncTask<Boolean> handle(TurnVirtualSocket.QueuedOutgoingFrame frame) {
                        return processQueuedFrame.apply(TurnVirtualSocket.this, frame);
                    }

                    @Override
                    public boolean isReady() {
                        return ackSent && hasReachableRecipient.apply(TurnVirtualSocket.this);
                    }
                },
                logger,
                "TURN priority outgoing queue blocked"
            );
        this.queuedOutgoingFrames =
            new BlockingPacketQueue<QueuedOutgoingFrame>(
                new BlockingPacketQueue.PacketHandler<TurnVirtualSocket.QueuedOutgoingFrame>() {
                    @Override
                    public AsyncTask<Boolean> handle(TurnVirtualSocket.QueuedOutgoingFrame frame) {
                        return processQueuedFrame.apply(TurnVirtualSocket.this, frame);
                    }

                    @Override
                    public boolean isReady() {
                        return ackSent && hasReachableRecipient.apply(TurnVirtualSocket.this);
                    }
                },
                logger,
                "TURN outgoing queue blocked"
            );
        // Do not drain any queued sender data before the connect ACK has been sent.
        this.queuedPriorityOutgoingFrames.stop();
        this.queuedOutgoingFrames.stop();
    }

    long getVsocketId() {
        return vsocketId;
    }

    NostrPublicKey getRoomPubkey() {
        return roomPubkey;
    }

    NostrPublicKey getClientPubkey() {
        return clientPubkey;
    }

    String getSourceSessionId() {
        return sourceSessionId;
    }

    String getTargetSessionId() {
        return targetSessionId;
    }

    String getProtocolId() {
        return protocolId;
    }

    String getApplicationId() {
        return applicationId;
    }

    String getChannelLabel() {
        return channelLabel;
    }

    LogicalIdentity getLogicalIdentity() {
        return logicalIdentity;
    }

    boolean enqueueOutgoing(byte[] frameBytes, int maxQueuedFrames, boolean priority) {
        int priorityQueued = this.queuedPriorityOutgoingFrames.size();
        int normalQueued = this.queuedOutgoingFrames.size();
        int totalQueued = priorityQueued + normalQueued;

        int reservedForPriority = maxQueuedFrames > 1 ? PRIORITY_RESERVED_SLOTS : 0;
        int normalCapacity = Math.max(0, maxQueuedFrames - reservedForPriority);

        if (priority) {
            if (totalQueued >= maxQueuedFrames) {
                return false;
            }
        } else {
            if (normalQueued >= normalCapacity || totalQueued >= maxQueuedFrames) {
                return false;
            }
        }

        BlockingPacketQueue<QueuedOutgoingFrame> queue = priority ? this.queuedPriorityOutgoingFrames : this.queuedOutgoingFrames;
        queue.enqueue(new QueuedOutgoingFrame(frameBytes));
        return true;
    }

    boolean out(ByteBuffer frame, int maxQueuedFrames) {
        return out(frame, maxQueuedFrames, false);
    }

    boolean out(ByteBuffer frame, int maxQueuedFrames, boolean priority) {
        if (frame == null) {
            return false;
        }
        byte[] frameBytes = new byte[frame.remaining()];
        frame.asReadOnlyBuffer().get(frameBytes);
        return enqueueOutgoing(frameBytes, maxQueuedFrames, priority);
    }

    ByteBuffer in(QueuedOutgoingFrame queued, long recipientVsocketId) {
        if (queued == null) {
            return null;
        }
        ByteBuffer queuedFrame = ByteBuffer.wrap(queued.getFrameBytes()).asReadOnlyBuffer();
        return NostrTURNCodec.withVsocketId(queuedFrame, recipientVsocketId);
    }

    void loop() {
        this.queuedPriorityOutgoingFrames.loop();
        this.queuedOutgoingFrames.loop();
    }

    void markAckSent() {
        this.ackSent = true;
        this.queuedPriorityOutgoingFrames.restart();
        this.queuedOutgoingFrames.restart();
    }

    boolean isAckSent() {
        return ackSent;
    }

    @Override
    public void close() {
        this.queuedPriorityOutgoingFrames.close();
        this.queuedOutgoingFrames.close();
    }

    /**
     * Two virtual sockets are reciprocal when one socket's sender is the other's target
     * and all routing dimensions match.
     *
     * <p>Routing dimensions intentionally include:
     * - room
     * - protocol id
     * - application id
     * - channel label
     * - inverted sender/target pubkeys
     *
     * <p>Session ids are part of reciprocity to avoid cross-routing when the same
     * pubkey has multiple active sessions.
     */
    boolean isReciprocal(TurnVirtualSocket other) {
        if (!this.roomPubkey.equals(other.roomPubkey)) {
            return false;
        }
        if (!this.protocolId.equals(other.protocolId)) {
            return false;
        }
        if (!this.applicationId.equals(other.applicationId)) {
            return false;
        }
        if (!this.channelLabel.equals(other.channelLabel)) {
            return false;
        }
        if (!this.clientPubkey.equals(other.targetPubkey)) {
            return false;
        }
        if (!this.targetPubkey.equals(other.clientPubkey)) {
            return false;
        }
        if (!this.sourceSessionId.equals(other.targetSessionId)) {
            return false;
        }
        if (!this.targetSessionId.equals(other.sourceSessionId)) {
            return false;
        }
        return true;
    }
}
