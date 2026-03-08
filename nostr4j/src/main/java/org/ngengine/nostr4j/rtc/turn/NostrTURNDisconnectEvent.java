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

package org.ngengine.nostr4j.rtc.turn;

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
        super("disconnect", localPeer, null, null, null);
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
        super("disconnect", event, null, null, null, null);
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
