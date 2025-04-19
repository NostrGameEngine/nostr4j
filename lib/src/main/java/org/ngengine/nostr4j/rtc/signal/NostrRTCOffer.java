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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.utils.NostrUtils;

/**
 * A webRTC offer to connect with a peer, with the peer pubkey, sdp and metadata.
 */
public class NostrRTCOffer implements NostrRTCSignal {

    private static final long serialVersionUID = 1L;

    private final NostrPublicKey pubkey;
    private final Map<String, Object> map;
    private final String offerString;
    private final String turnServer;
    private transient NostrRTCPeer peerInfo;

    public NostrRTCOffer(NostrPublicKey pubkey, String offerString, String turnServer, Map<String, Object> misc) {
        this.pubkey = pubkey;
        this.offerString = offerString;
        this.turnServer = turnServer;
        if(turnServer.isEmpty()) {
            throw new IllegalArgumentException("Turn server cannot be empty");
        }
        HashMap<String, Object> map = new HashMap<>();
        if (misc != null && !misc.isEmpty()) {
            map.putAll(misc);
        }
        map.put("offer", this.offerString);
        if (turnServer != null && !turnServer.isEmpty()) {
            map.put("turn", turnServer);
        } else {
            map.remove("turn");
        }

        this.map = Collections.unmodifiableMap(map);
    }

    public NostrRTCOffer(NostrPublicKey pubkey, Map<String, Object> map) {
        this(pubkey, NostrUtils.safeString(map.get("offer")), NostrUtils.safeString(map.get("turn")), map);
    }

    public String getOfferString() {
        return this.offerString;
    }

    public String getTurnServer() {
        return this.turnServer;
    }

    public Map<String, Object> get() {
        return this.map;
    }

    public NostrRTCPeer getPeerInfo() {
        if (peerInfo != null) return peerInfo;
        peerInfo = new NostrRTCPeer(pubkey, this.turnServer, this.map);
        return peerInfo;
    }

    @Override
    public String toString() {
        return (
            "NostrRTCOffer{" +
            "pubkey=" +
            pubkey +
            ", map=" +
            Arrays.deepToString(map.entrySet().toArray()) +
            ", offerString='" +
            offerString +
            '\'' +
            ", turnServer='" +
            turnServer +
            '\'' +
            ", peerInfo=" +
            peerInfo +
            '}'
        );
    }
}
