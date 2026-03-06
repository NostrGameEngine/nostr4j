package org.ngengine.nostr4j.rtc.turn.event;

import java.util.HashMap;
import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class NostrTURNDisconnectEvent extends NostrTURNEvent {
    private final long vsocketId;
    private final String reason;
    private final boolean error;

    public static NostrTURNDisconnectEvent createDisconnect(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        long vSocketId,
        String reason,
        boolean error
    ) {
        return new NostrTURNDisconnectEvent(localPeer, roomKeyPair, remotePeer, channelLabel, vSocketId, reason, error);
    }
    private NostrTURNDisconnectEvent(
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrRTCPeer remotePeer,
        String channelLabel,
        long vsocketId,
        String reason,
        boolean error
    ) {
        super(
            "disconnect",
            localPeer, 
            null,
            null,
            null
        );    
        this.vsocketId = vsocketId;
        if (this.vsocketId == 0L) {
            throw new IllegalArgumentException("Invalid disconnect vsocketId: must be != 0");
        }
        this.reason = NGEUtils.safeString(reason);
        this.error = error;
    }

    public static NostrTURNDisconnectEvent parseIncoming(
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrRTCPeer remotePeer,
        String channelLabel,
        long envelopeVsocketId
    ) {
        return new NostrTURNDisconnectEvent(event, localPeer, roomKeyPair, remotePeer, channelLabel, envelopeVsocketId);
    }
    private NostrTURNDisconnectEvent(
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrRTCPeer remotePeer,
        String channelLabel,
        long envelopeVsocketId
    ) {
        super(
            "disconnect",
            event,
            null,
            null,
            null,
            null
        );         
        Map<String, Object> content = NGEPlatform.get().fromJSON(event.getContent(), Map.class);
        this.vsocketId = envelopeVsocketId;
        this.reason = NGEUtils.safeString(content.get("reason"));
        this.error = NGEUtils.safeBool(content.get("error"));
    }

    public long getVsocketId() {
        return vsocketId;
    }

    public String getReason() {
        return reason;
    }

    public boolean isError() {
        return error;
    }

     
    @Override
    protected AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        Map<String, Object> content = new HashMap<>();
        content.put("reason", reason);
        content.put("error", Boolean.valueOf(error));
        event.withContent(NGEPlatform.get().toJSON(content));
        return AsyncTask.completed(event);
    }

    @Override
    protected boolean shouldIncludeRoutingTags() {
        return false;
    }

    @Override
    protected long getEnvelopeVsocketId() {
        return vsocketId;
    }
}
