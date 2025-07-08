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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

/**
 * All the info about a peer in the swarm.
 */
public class NostrRTCPeer {

    private final NostrPublicKey pubkey;
    private final Map<String, Object> misc;

    private final String turnServer;
    private Instant lastSeen;
    private Map<String, Object> publicMisc;

    NostrRTCPeer(NostrPublicKey pubkey, String turnServer, Map<String, Object> misc) {
        Objects.requireNonNull(turnServer);
        Objects.requireNonNull(pubkey);
        Objects.requireNonNull(misc);
        if (turnServer.isEmpty()) {
            throw new IllegalArgumentException("Turn server cannot be empty");
        }

        this.pubkey = pubkey;
        this.turnServer = turnServer;

        this.misc = new HashMap<>(misc);
    }

    public NostrPublicKey getPubkey() {
        return pubkey;
    }

    public Map<String, Object> getMisc() {
        return misc;
    }

    public Map<String, Object> getPublicMisc() {
        if (publicMisc == null) {
            publicMisc = new HashMap<>();
            for (Entry<String, Object> entry : misc.entrySet()) {
                if (entry.getKey().startsWith("public:")) {
                    publicMisc.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return publicMisc;
    }

    public String getTurnServer() {
        return turnServer;
    }

    public Instant getLastSeen() {
        if (lastSeen == null) return Instant.now();
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        NostrRTCPeer that = (NostrRTCPeer) obj;
        return pubkey.equals(that.pubkey);
    }

    @Override
    public int hashCode() {
        return pubkey.hashCode();
    }

    @Override
    public String toString() {
        return (
            "NostrRTCPeer{" +
            "pubkey=" +
            pubkey +
            ", misc=" +
            misc +
            ", turnServer='" +
            turnServer +
            '\'' +
            ", lastSeen=" +
            lastSeen +
            '}'
        );
    }
}
