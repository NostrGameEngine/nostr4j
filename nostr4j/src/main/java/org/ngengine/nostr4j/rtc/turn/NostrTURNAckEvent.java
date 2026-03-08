/**
 * BSD 3-Clause License
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.turn;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.platform.AsyncTask;

/**
 * TURN ack event (`t=ack`).
 */
public final class NostrTURNAckEvent extends NostrTURNEvent {

    private final long vsocketId;

    public static NostrTURNAckEvent createAck(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long vsocketId
    ) {
        return new NostrTURNAckEvent(localPeer, remotePeer, roomKeyPair, channelLabel, vsocketId);
    }

    private NostrTURNAckEvent(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long vsocketId
    ) {
        super("ack", localPeer, null, null, null);
        this.vsocketId = vsocketId;
        if (this.vsocketId == 0L) {
            throw new IllegalArgumentException("Invalid ack vsocketId: must be != 0");
        }
    }

    public static NostrTURNAckEvent parseIncoming(SignedNostrEvent event, long envelopeVsocketId) {
        return new NostrTURNAckEvent(event, envelopeVsocketId);
    }

    private NostrTURNAckEvent(SignedNostrEvent event, long envelopeVsocketId) {
        super("ack", event, null, null, null, null);
        this.vsocketId = envelopeVsocketId;
    }

    public long getVsocketId() {
        return vsocketId;
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
}
