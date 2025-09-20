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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

/**
 * A collection of valid ice candidates that can be used to establish a
 * connection with a peer.
 */
public class NostrRTCIceCandidate implements NostrRTCSignal {

    private static final long serialVersionUID = 1L;

    private final NostrPublicKey pubkey;
    private final Map<String, Object> map;
    private final Collection<RTCTransportIceCandidate> candidates;

    public NostrRTCIceCandidate(
        NostrPublicKey pubkey,
        Collection<RTCTransportIceCandidate> candidates,
        Map<String, Object> misc
    ) {
        this.candidates = Collections.unmodifiableCollection(candidates);
        HashMap<String, Object> map = new HashMap<>();
        if (misc != null && !misc.isEmpty()) {
            map.putAll(misc);
        }
        candidatesToMap(map, this.candidates);
        this.map = Collections.unmodifiableMap(map);
        this.pubkey = pubkey;
    }

    public NostrRTCIceCandidate(NostrPublicKey pubkey, Map<String, Object> map) {
        this(pubkey, candidatesFromMap(map), map);
    }

    private static void candidatesToMap(Map<String, Object> map, Collection<RTCTransportIceCandidate> candidates) {
        ArrayList<Map<String, Object>> cs = new ArrayList<>();
        for (RTCTransportIceCandidate c : candidates) {
            HashMap<String, Object> cm = new HashMap<>();
            cm.put("candidate", c.getCandidate());
            cm.put("sdpMid", c.getSdpMid());
            cs.add(cm);
        }
        map.put("candidates", cs);
    }

    private static Collection<RTCTransportIceCandidate> candidatesFromMap(Map<String, Object> map) {
        ArrayList<RTCTransportIceCandidate> candidates = new ArrayList<>();
        Collection<Map<String, Object>> cs = (Collection<Map<String, Object>>) map.get("candidates");
        for (Map<String, Object> c : cs) {
            String candidate = NGEUtils.safeString(c.get("candidate"));
            String sdpMid = NGEUtils.safeString(c.get("sdpMid"));
            candidates.add(new RTCTransportIceCandidate(candidate, sdpMid));
        }
        return candidates;
    }

    public Collection<RTCTransportIceCandidate> getCandidates() {
        return this.candidates;
    }

    public Map<String, Object> get() {
        return this.map;
    }

    @Override
    public String toString() {
        return (
            "NostrRTCOffer{" +
            "pubkey=" +
            pubkey +
            ", map=" +
            Arrays.deepToString(map.entrySet().toArray()) +
            ", candidates='" +
            Arrays.deepToString(candidates.toArray()) +
            '\'' +
            '}'
        );
    }

    public NostrPublicKey getPubkey() {
        return pubkey;
    }
}
