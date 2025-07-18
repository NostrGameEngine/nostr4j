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
import org.ngengine.nostr4j.keypair.NostrPublicKey;

/**
 * Announce the peer can accept connections.
 */
public class NostrRTCAnnounce implements NostrRTCSignal {

    private static final long serialVersionUID = 1L;

    private final NostrPublicKey publicKey;
    private final Map<String, Object> misc;
    private volatile Instant expireAt;

    public NostrRTCAnnounce(NostrPublicKey publicKey, Instant expireAt, Map<String, Object> misc) {
        this.publicKey = publicKey;
        this.expireAt = expireAt;
        HashMap<String, Object> map = new HashMap<>();
        if (misc != null && !misc.isEmpty()) {
            map.putAll(misc);
        }
        this.misc = map;
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
        return misc;
    }

    public NostrPublicKey getPubkey() {
        return publicKey;
    }

    @Override
    public String toString() {
        return "NostrRTCAnnounce{" + "publicKey=" + publicKey + ", expireAt=" + expireAt + '}';
    }
}
