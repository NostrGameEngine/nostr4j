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

package org.ngengine.nostr4j.rtc.turn.event;

import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.utils.NostrRoomProof;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class NostrTURNConnectEvent extends NostrTURNEvent {

    private final String challenge;
    private final int diff;
    private final long vsocketId;

    public static NostrTURNConnectEvent createConnect(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        String challenge,
        long vsocketId,
        int requiredDiff
    ) {
        return new NostrTURNConnectEvent(localPeer, remotePeer, roomKeyPair, channelLabel, challenge, vsocketId, requiredDiff);
    }

    private NostrTURNConnectEvent(
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        String challenge,
        long vsocketId,
        int requiredDiff
    ) {
        super("connect", localPeer, remotePeer, roomKeyPair, channelLabel);
        this.challenge = NGEUtils.safeString(challenge);
        this.vsocketId = vsocketId;
        if (this.vsocketId == 0L) {
            throw new IllegalArgumentException("Invalid TURN connect event: vsocketId must be != 0");
        }
        this.diff = NGEUtils.safeInt(requiredDiff);
    }

    public static NostrTURNConnectEvent parseIncoming(
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        int expectedDifficulty,
        String expectedChallenge,
        long expectedVsocketId
    ) {
        return new NostrTURNConnectEvent(
            event,
            localPeer,
            remotePeer,
            roomKeyPair,
            channelLabel,
            expectedDifficulty,
            expectedChallenge,
            expectedVsocketId
        );
    }

    private NostrTURNConnectEvent(
        SignedNostrEvent event,
        NostrRTCLocalPeer localPeer,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        String channelLabel,
        int expectedDifficulty,
        String expectedChallenge,
        long expectedVsocketId
    ) {
        super("connect", event, localPeer, roomKeyPair, remotePeer, channelLabel);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = NGEPlatform.get().fromJSON(event.getContent(), Map.class);

        this.challenge = NGEUtils.safeString(content.get("challenge"));
        if (!this.challenge.equals(NGEUtils.safeString(expectedChallenge))) {
            throw new IllegalArgumentException(
                "Invalid TURN connect event: challenge mismatch (expected " +
                expectedChallenge +
                ", got " +
                this.challenge +
                ")"
            );
        }

        long contentVsocketId = NGEUtils.safeLong(content.get("vsocketId"));
        this.vsocketId = contentVsocketId;
        if (this.vsocketId == 0L) {
            throw new IllegalArgumentException("Invalid TURN connect event: vsocketId must be != 0");
        }
        if (expectedVsocketId != 0L && this.vsocketId != expectedVsocketId) {
            throw new IllegalArgumentException(
                "Invalid TURN connect event: expected vsocketId " + expectedVsocketId + ", got " + this.vsocketId
            );
        }
        this.diff = NGEUtils.safeInt(expectedDifficulty);
        int diffNonce = NGEUtils.safeInt(event.getFirstTagSecondValue("nonce"));
        if (diffNonce != this.diff) {
            throw new IllegalArgumentException(
                "Invalid TURN connect event: proof-of-work difficulty mismatch (expected " +
                expectedDifficulty +
                ", got " +
                diffNonce +
                ")"
            );
        }

        if (!event.checkPow(this.diff)) {
            throw new IllegalArgumentException(
                "Invalid TURN connect event: proof-of-work does not meet expected difficulty of " + expectedDifficulty
            );
        }
    }

    public String getChallenge() {
        return challenge;
    }

    @Override
    protected AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        Map<String, Object> content = new HashMap<>();
        content.put("challenge", challenge);
        content.put("vsocketId", Long.toString(vsocketId));
        event.withContent(NGEPlatform.get().toJSON(content));

        NostrRTCLocalPeer localPeer = getLocalPeer();
        NostrKeyPair roomKeyPair = getRoomKeyPair();

        return localPeer
            .getSigner()
            .getPublicKey()
            .compose(senderPubkey -> {
                return NostrRoomProof
                    .sign(roomKeyPair, event.getCreatedAt(), event.getKind(), senderPubkey, challenge)
                    .then(sig -> {
                        String id = NostrRoomProof.computeId(
                            roomKeyPair.getPublicKey(),
                            event.getCreatedAt(),
                            event.getKind(),
                            senderPubkey,
                            challenge
                        );
                        event.withTag("roomproof", id, sig);
                        return event;
                    });
            });
    }

    @Override
    public AsyncTask<SignedNostrEvent> toEvent() {
        if (diff <= 0) {
            return super.toEvent();
        }
        return toPowEvent(diff);
    }

    @Override
    protected long getEnvelopeVsocketId() {
        return vsocketId;
    }
}
