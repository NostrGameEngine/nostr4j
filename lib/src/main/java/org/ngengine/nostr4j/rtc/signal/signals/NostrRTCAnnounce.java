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
package org.ngengine.nostr4j.rtc.signal.signals;

import java.time.Instant;
import java.util.Map;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.NostrRTCPeer;

public class NostrRTCAnnounce implements NostrRTCSignal {

    private static final long serialVersionUID = 1L;

    private final NostrPublicKey publicKey;
    private volatile Instant expireAt;
    private transient NostrRTCPeer peerInfo;

    public NostrRTCAnnounce(NostrPublicKey publicKey, Instant expireAt) {
        this.publicKey = publicKey;
        this.expireAt = expireAt;
    }

    public void updateExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expireAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NostrRTCAnnounce)) return false;

        NostrRTCAnnounce that = (NostrRTCAnnounce) o;

        if (!publicKey.equals(that.publicKey)) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return publicKey.hashCode();
    }

    public Map<String, Object> get() {
        return Map.of();
    }

    public NostrRTCPeer getPeerInfo() {
        if (peerInfo != null) return peerInfo;
        peerInfo = new NostrRTCPeer(publicKey, "", Map.of());
        return peerInfo;
    }

    @Override
    public String toString() {
        return "NostrRTCAnnounce{" + "publicKey=" + publicKey + ", expireAt=" + expireAt + '}';
    }
}
