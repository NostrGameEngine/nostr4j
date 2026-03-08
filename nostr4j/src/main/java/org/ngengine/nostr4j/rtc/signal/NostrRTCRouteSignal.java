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
package org.ngengine.nostr4j.rtc.signal;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

/**
 * A collection of valid ice candidates that can be used to establish a
 * connection with a peer.
 */
public final class NostrRTCRouteSignal extends NostrRTCSignal {

    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(
        NostrRTCRouteSignal.class.getName()
    );
    private static final long serialVersionUID = 2L;

    private static class Route {

        public Collection<RTCTransportIceCandidate> candidates;
        public String turnServer;

        Route(Collection<RTCTransportIceCandidate> candidates, String turnServer) {
            this.candidates = Collections.unmodifiableCollection(candidates);
            this.turnServer = turnServer;
        }
    }

    private final AsyncTask<Route> route;

    public NostrRTCRouteSignal(
        NostrSigner localSigner,
        NostrKeyPair roomKeyPair,
        NostrRTCPeer peer,
        Collection<RTCTransportIceCandidate> candidates,
        @Nullable String turnServer
    ) {
        super(localSigner, "route", roomKeyPair, peer);
        Collection<RTCTransportIceCandidate> safeCandidates = candidates == null
            ? Collections.emptyList()
            : Collections.unmodifiableCollection(candidates);
        route = AsyncTask.completed(new Route(safeCandidates, turnServer));
    }

    public NostrRTCRouteSignal(NostrSigner localSigner, NostrKeyPair roomKeyPair, SignedNostrEvent event) {
        super(localSigner, "route", roomKeyPair, event);
        route =
            decrypt(event.getContent(), event.getPubkey())
                .then(decrypted -> {
                    Map<String, Object> map = NGEPlatform.get().fromJSON(decrypted, Map.class);
                    String turn = NGEUtils.safeString(map.get("turn"));
                    if (turn.trim().isEmpty()) turn = null;
                    ArrayList<RTCTransportIceCandidate> candidates = new ArrayList<>();
                    Collection<Map<String, Object>> cs = (Collection<Map<String, Object>>) map.get("candidates");
                    if (cs != null) {
                        for (Map<String, Object> c : cs) {
                            if (c == null) continue;
                            String candidate = NGEUtils.safeString(c.get("candidate"));
                            String sdpMid = NGEUtils.safeString(c.get("sdpMid"));
                            candidates.add(new RTCTransportIceCandidate(candidate, sdpMid));
                        }
                    }
                    return new Route(candidates, turn);
                });
    }

    public Collection<RTCTransportIceCandidate> getCandidates() {
        return NGEUtils.awaitNoThrow(route).candidates;
    }

    @Nullable
    public String getTurnServer() {
        return NGEUtils.awaitNoThrow(route).turnServer;
    }

    @Override
    public void await() {
        NGEUtils.awaitNoThrow(route);
    }

    @Override
    protected final AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        Map<String, Object> map = new HashMap<>();
        map.put("turn", getTurnServer());
        ArrayList<Map<String, Object>> cs = new ArrayList<>();
        for (RTCTransportIceCandidate c : getCandidates()) {
            HashMap<String, Object> cm = new HashMap<>();
            cm.put("candidate", c.getCandidate());
            cm.put("sdpMid", c.getSdpMid());
            cs.add(cm);
        }
        map.put("candidates", cs);

        String data = NGEPlatform.get().toJSON(map);
        event.withContent(data);
        return AsyncTask.completed(event);
    }

    public void updatePeer(NostrRTCPeer peer) {
        try {
            peer.setTurnServer(getTurnServer());
        } catch (Exception e) {
            if (logger.isLoggable(java.util.logging.Level.WARNING)) {
                logger.log(java.util.logging.Level.WARNING, "Unable to set turn server", e);
            }
        }
    }

    @Override
    protected final boolean requireRoomSignature() {
        return true;
    }
}
