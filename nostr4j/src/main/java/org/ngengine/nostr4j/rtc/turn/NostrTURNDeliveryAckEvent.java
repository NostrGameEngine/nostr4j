/**
 * BSD 3-Clause License
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.turn;

import java.nio.ByteBuffer;
import java.util.Collection;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.platform.AsyncTask;

/**
 * TURN delivery ack event (`t=delivery_ack`).
 *
 * The signed header can be reused across frames. The acknowledged packet id
 * is encoded in the frame envelope MESSAGE_ID.
 */
public final class NostrTURNDeliveryAckEvent extends NostrTURNEvent {

    private final long vsocketId;
    private final int messageId;
    private volatile AsyncTask<SignedNostrEvent> event;

    public static NostrTURNDeliveryAckEvent createOutgoing(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long vsocketId
    ) {
        return new NostrTURNDeliveryAckEvent(localPeer, remotePeer, roomKeyPair, channelLabel, vsocketId);
    }

    private NostrTURNDeliveryAckEvent(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long vsocketId
    ) {
        super("delivery_ack", localPeer, remotePeer, roomKeyPair, channelLabel);
        this.vsocketId = vsocketId;
        this.messageId = 0;
        if (this.vsocketId == 0L) {
            throw new IllegalArgumentException("Invalid delivery_ack vsocketId: must be != 0");
        }
    }

    public static NostrTURNDeliveryAckEvent parseIncoming(
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        long envelopeVsocketId,
        int envelopeMessageId
    ) {
        return new NostrTURNDeliveryAckEvent(event, localPeer, remotePeer, envelopeVsocketId, envelopeMessageId);
    }

    private NostrTURNDeliveryAckEvent(
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        long envelopeVsocketId,
        int envelopeMessageId
    ) {
        super("delivery_ack", event, localPeer, null, null, null);
        this.vsocketId = envelopeVsocketId;
        this.messageId = envelopeMessageId;
        if (this.vsocketId == 0L) {
            throw new IllegalArgumentException("Invalid delivery_ack vsocketId: must be != 0");
        }
        if (this.messageId == 0) {
            throw new IllegalArgumentException("Invalid delivery_ack messageId: must be != 0");
        }
        if (remotePeer == null) {
            throw new IllegalArgumentException("Remote peer is required for TURN delivery_ack event");
        }
        NostrPublicKey senderPubkey = event.getPubkey();
        if (!remotePeer.getPubkey().equals(senderPubkey)) {
            throw new IllegalArgumentException("TURN delivery_ack event sender mismatch");
        }
    }

    public long getVsocketId() {
        return vsocketId;
    }

    public int getMessageId() {
        return messageId;
    }

    @Override
    protected boolean shouldIncludeRoutingTags() {
        return false;
    }

    @Override
    protected long getEnvelopeVsocketId() {
        return vsocketId;
    }

    @Override
    protected AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        event.withContent("");
        return AsyncTask.completed(event);
    }

    @Override
    public AsyncTask<SignedNostrEvent> toEvent() {
        if (event == null) {
            synchronized(this){
                if(event==null){ // make sure _really_ reuse it even on 
                                //  racing calls (make behavior more deterministic)
                    event = super.toEvent();
                }
            }
        }
        return event;
    }

    public AsyncTask<ByteBuffer> encodeToFrame(Collection<ByteBuffer> payloads, int ackMessageId) {
        if (ackMessageId == 0) {
            throw new IllegalArgumentException("TURN delivery_ack messageId must be != 0");
        }
        return toEncodedHeader()
            .then(header -> {
                return NostrTURNCodec.encodeFrame(header, getEnvelopeVsocketId(), ackMessageId, null);
            });
    }
}
