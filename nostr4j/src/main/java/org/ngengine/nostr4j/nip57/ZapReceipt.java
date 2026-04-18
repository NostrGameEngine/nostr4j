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

package org.ngengine.nostr4j.nip57;

import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEPlatform;

public class ZapReceipt extends SignedNostrEvent {

    private transient ZapRequest zapRequestEventCache = null;

    public ZapReceipt(Map<String, Object> map) {
        super(Objects.requireNonNull(map, "map"));
    }

    @Nullable
    public String getPreimage() {
        return getFirstTagFirstValue("preimage");
    }

    public String getBolt11() {
        String bolt11 = getFirstTagFirstValue("bolt11");
        if (bolt11 == null || bolt11.isEmpty()) {
            throw new IllegalStateException("Missing required bolt11 tag in zap receipt");
        }
        return bolt11;
    }

    public NostrPublicKey getProviderPubkey() {
        return getPubkey();
    }

    public ZapRequest getZapRequestEvent() {
        if (zapRequestEventCache != null) return zapRequestEventCache;
        String zapRequestRaw = getFirstTagFirstValue("description");
        if (zapRequestRaw == null || zapRequestRaw.isEmpty()) {
            throw new IllegalStateException("Missing required description tag in zap receipt");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> zapRequestMap = (Map<String, Object>) NGEPlatform.get().fromJSON(zapRequestRaw, Map.class);
        zapRequestEventCache = new ZapRequest(zapRequestMap);
        return zapRequestEventCache;
    }

    @Nullable
    public NostrEvent.Coordinates getZappedEventCoordinates() {
        String eTag = getFirstTagFirstValue("e");
        String kTag = getFirstTagFirstValue("k");
        String aTag = getFirstTagFirstValue("a");
        if (eTag != null) {
            return new NostrEvent.Coordinates("e", kTag != null ? kTag : "", eTag);
        } else if (aTag != null) {
            return new NostrEvent.Coordinates("a", kTag != null ? kTag : "", aTag);
        }
        return null;
    }

    public NostrPublicKey getRecipient() {
        String recipient = getFirstTagFirstValue("p");
        if (recipient == null || recipient.isEmpty()) {
            throw new IllegalStateException("Missing required p tag in zap receipt");
        }
        return NostrPublicKey.fromHex(recipient);
    }

    @Nullable
    public NostrPublicKey getSender() {
        if (hasTag("P")) {
            return NostrPublicKey.fromHex(getFirstTagFirstValue("P"));
        }
        return null;
    }
}
