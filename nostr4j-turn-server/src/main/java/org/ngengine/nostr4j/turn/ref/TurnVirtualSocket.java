package org.ngengine.nostr4j.turn.ref;

import org.ngengine.nostr4j.keypair.NostrPublicKey;

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
final class TurnVirtualSocket {
    // Client-generated and server-accepted virtual socket id for this websocket session.
    private final long vsocketId;
    // Room boundary for routing isolation.
    private final NostrPublicKey roomPubkey;
    // Authenticated sender pubkey from the signed TURN event header.
    private final NostrPublicKey clientPubkey;
    // Destination pubkey declared in the connect routing tag.
    private final NostrPublicKey targetPubkey;
    // Sender session id (`d` tag) - allows same pubkey with multiple sessions.
    private final String sessionId;
    // Protocol/application dimensions (`i` / `y`) for namespace isolation.
    private final String protocolId;
    private final String applicationId;
    // Channel label (`p` tag second value) for per-channel multiplexing.
    private final String channelLabel;

    TurnVirtualSocket(
        long vsocketId,
        NostrPublicKey roomPubkey,
        NostrPublicKey clientPubkey,
        NostrPublicKey targetPubkey,
        String sessionId,
        String protocolId,
        String applicationId,
        String channelLabel
    ) {
        this.vsocketId = vsocketId;
        this.roomPubkey = roomPubkey;
        this.clientPubkey = clientPubkey;
        this.targetPubkey = targetPubkey;
        this.sessionId = sessionId;
        this.protocolId = protocolId;
        this.applicationId = applicationId;
        this.channelLabel = channelLabel;
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

    String getSessionId() {
        return sessionId;
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
     * <p>Session id is not used in reciprocity here because each socket already
     * belongs to a concrete websocket-scoped accepted connect tuple, and reciprocity
     * is checked against that concrete accepted entry.
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
        return true;
    }
}
