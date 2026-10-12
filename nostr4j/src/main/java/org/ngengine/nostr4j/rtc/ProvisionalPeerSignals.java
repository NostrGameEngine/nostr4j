/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

package org.ngengine.nostr4j.rtc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignal;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

/**
 * State-only registry. The room monitor serializes retention, promotion and close.
 */
final class ProvisionalPeerSignals {

    static final class Pending {

        final long firstSeen;
        NostrRTCOfferSignal offer;
        final List<NostrRTCRouteSignal> routes = new ArrayList<>();

        Pending(long firstSeen) {
            this.firstSeen = firstSeen;
        }
    }

    private final LongSupplier clock;
    private final RTCSessionLimits limits;
    private final Map<NostrRTCPeer, Pending> peers = new LinkedHashMap<>();

    ProvisionalPeerSignals(LongSupplier clock) {
        this(clock, RTCSessionLimits.getDefault());
    }

    ProvisionalPeerSignals(LongSupplier clock, RTCSessionLimits limits) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Await/decode and validate before taking the room monitor. Signaling already authenticated these objects.
     */
    static boolean retainable(NostrRTCSignal signal) {
        if (
            signal == null ||
            signal.getPeer() == null ||
            signal.getPeer().getPubkey() == null ||
            !RTCSessionLimits.isValidSessionId(signal.getPeer().getSessionId())
        ) return false;
        if (signal instanceof NostrRTCOfferSignal) {
            String offer = ((NostrRTCOfferSignal) signal).getOfferString();
            return offer != null && offer.length() <= RTCSessionLimits.MAX_PROVISIONAL_OFFER_CHARS;
        }
        if (!(signal instanceof NostrRTCRouteSignal)) return false;
        NostrRTCRouteSignal route = (NostrRTCRouteSignal) signal;
        String turn = route.getTurnServer();
        if (turn != null && turn.length() > RTCSessionLimits.MAX_PROVISIONAL_TURN_URL_CHARS) return false;
        Collection<RTCTransportIceCandidate> candidates = route.getCandidates();
        if (candidates == null || candidates.size() > RTCSessionLimits.MAX_PROVISIONAL_CANDIDATES_PER_ROUTE) return false;
        long routeChars = turn == null ? 0L : turn.length();
        for (RTCTransportIceCandidate candidate : candidates) {
            if (
                candidate == null ||
                candidate.getCandidate() == null ||
                candidate.getSdpMid() == null ||
                candidate.getCandidate().length() > RTCSessionLimits.MAX_PROVISIONAL_CANDIDATE_CHARS ||
                candidate.getSdpMid().length() > RTCSessionLimits.MAX_PROVISIONAL_SDP_MID_CHARS
            ) return false;
            routeChars += candidate.getCandidate().length() + candidate.getSdpMid().length();
            if (routeChars > RTCSessionLimits.MAX_PROVISIONAL_ROUTE_CHARS) return false;
        }
        return true;
    }

    /**
     * Caller validates payload outside the room monitor with retainable before entering this state-only method.
     */
    boolean retain(NostrRTCSignal signal) {
        expire();
        NostrRTCPeer peer = signal.getPeer();
        Pending pending = peers.get(peer);
        if (pending == null) {
            if (peers.size() >= limits.getMaxProvisionalPeers()) return false;
            int identityCount = 0;
            for (NostrRTCPeer existing : peers.keySet()) {
                if (existing.getPubkey().equals(peer.getPubkey())) identityCount++;
            }
            if (identityCount >= limits.getMaxProvisionalSessionsPerIdentity()) return false;
            pending = new Pending(clock.getAsLong());
            peers.put(peer, pending);
        }
        // Duplicate/changing signals never extend the first-seen lease.
        if (signal instanceof NostrRTCOfferSignal) {
            if (pending.offer == null) pending.offer = (NostrRTCOfferSignal) signal;
            return true;
        }
        if (signal instanceof NostrRTCRouteSignal) {
            if (pending.routes.size() == RTCSessionLimits.MAX_PROVISIONAL_ROUTES) pending.routes.remove(0);
            pending.routes.add((NostrRTCRouteSignal) signal);
            return true;
        }
        return false;
    }

    Pending take(NostrRTCPeer peer) {
        expire();
        return peers.remove(peer);
    }

    void remove(NostrRTCPeer peer) {
        peers.remove(peer);
    }

    void removeIdentity(org.ngengine.nostr4j.keypair.NostrPublicKey key) {
        peers.keySet().removeIf(peer -> peer.getPubkey().equals(key));
    }

    void expire() {
        long now = clock.getAsLong();
        peers.values().removeIf(pending -> now - pending.firstSeen >= limits.getProvisionalLifetime().toMillis());
    }

    int size() {
        return peers.size();
    }

    void clear() {
        peers.clear();
    }
}
