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
import org.ngengine.platform.NGEUtils;

/**
 * An answer to an offer with the peer pubkey, sdp and metadata.
 */
public class NostrRTCAnswer implements NostrRTCSignal {

    private static final long serialVersionUID = 1L;

    private final NostrPublicKey pubkey;
    private final Map<String, Object> map;
    private final String sdp;
    private final String turnServer;

    private transient NostrRTCPeer peerInfo;

    public NostrRTCAnswer(NostrPublicKey pubkey, String sdp, String turnServer, Map<String, Object> misc) {
        this.pubkey = pubkey;
        this.sdp = sdp;
        this.turnServer = turnServer;
        HashMap<String, Object> map = new HashMap<>();
        if (misc != null && !misc.isEmpty()) {
            map.putAll(misc);
        }
        map.put("sdp", this.sdp);
        if (turnServer != null && !turnServer.isEmpty()) {
            map.put("turn", turnServer);
        } else {
            map.remove("turn");
        }
        this.map = Collections.unmodifiableMap(map);
    }

    public NostrRTCAnswer(NostrPublicKey pubkey, Map<String, Object> map) {
        this(pubkey, NGEUtils.safeString(map.get("sdp")), NGEUtils.safeString(map.get("turn")), map);
    }

    public String getSdp() {
        return this.sdp;
    }

    public String getTurnServers() {
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
            "NostrRTCAnswer{" +
            "pubkey=" +
            pubkey +
            ", sdp='" +
            sdp +
            '\'' +
            ", turnServer='" +
            turnServer +
            '\'' +
            ", map=" +
            Arrays.deepToString(map.entrySet().toArray()) +
            ", peerInfo=" +
            peerInfo +
            '}'
        );
    }
}
