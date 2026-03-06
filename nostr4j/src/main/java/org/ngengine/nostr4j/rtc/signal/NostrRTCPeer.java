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
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

/**
 * Creates a new peer with the given information.
 * <p>
 * A peer is uniquely identified by the tuple (pubkey, applicationId, protocolId, sessionId, roomPubkey). This combination
 * is used to route messages to the correct peer and to distinguish between multiple sessions from the same pubkey.
 * <p>
 * Note: All fields except {@code pubkey} and {@code roomPubkey} can be freely chosen by applications. 
 * If two different applications donnect to a public room sharing the same user signer, they may impersonate one another. 
 * If this is a concern, generate a random keypair and use that instead of the user-provided one (authentication can be handled off-protocol).
 */
public class NostrRTCPeer {

    private final NostrPublicKey pubkey;
    private Instant lastSeen;
    private final String applicationId;
    private final String protocolId;
    private final String sessionId;
    private final NostrPublicKey roomPubkey;
    private String turnServer;

    /**
     * Creates a new peer with the given information.
     *
     * @param pubkey      Peer's public key (must not be null)
     * @param applicationId Application ID (e.g., "com.myapp.rtc") (must not be null)
     * @param protocolId  Protocol ID used by the application (e.g., "mygame-01") (must not be null)
     * @param sessionId   Session ID (unique per peer session, e.g., random UUID created on application startup) (must not be null)
     * @param roomPubkey  Room public key (identifies the room the peer is in) (must not be null)
     * @param turnServer  TURN server URL, or {@code null} if unknown
     */
    public NostrRTCPeer(
        NostrPublicKey pubkey, 
        String applicationId,
        String protocolId,
        String sessionId,
        NostrPublicKey roomPubkey,
        @Nullable String turnServer
    ) {
        this.pubkey = Objects.requireNonNull(pubkey, "Pubkey cannot be null");
        this.applicationId = Objects.requireNonNull(applicationId, "Application ID cannot be null");
        this.protocolId = Objects.requireNonNull(protocolId, "Protocol ID cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "Session ID cannot be null");
        this.roomPubkey = Objects.requireNonNull(roomPubkey, "Room pubkey cannot be null");
        this.turnServer = turnServer;
    }

    void setTurnServer(String turnServer) {
        this.turnServer = turnServer;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getProtocolId() {
        return protocolId;
    }

    public String getSessionId() {
        return sessionId;
    }

    @Nullable
    public String getTurnServer() {
        return turnServer;
    }

 
    public NostrPublicKey getPubkey() {
        return pubkey;
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
        if(!pubkey.equals(that.pubkey)) return false;
        if(!applicationId.equals(that.applicationId)) return false;
        if(!protocolId.equals(that.protocolId)) return false;
        if(!sessionId.equals(that.sessionId)) return false;    
        if(!roomPubkey.equals(that.roomPubkey)) return false;  
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pubkey, applicationId, protocolId, sessionId, roomPubkey);
    }

    @Override
    public String toString() {
        return (
            "NostrRTCPeer{" +
            "pubkey=" +
            pubkey +
            ", applicationId='" +
            applicationId +
            '\'' +
            ", protocolId='" +
            protocolId +
            '\'' +
            ", sessionId='" +
            sessionId +
            '\'' +
            ", turnServer='" +
            turnServer +
            '\'' +
            ", lastSeen=" +
            lastSeen +      
            
            '}'
        );
    }
}
