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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEUtils;

public class ZapRequest extends SignedNostrEvent {

    public ZapRequest(Map<String, Object> map) {
        super(Objects.requireNonNull(map, "map"));
    }

    public long getAmountMsats() {
        return NGEUtils.safeMSats(getFirstTagFirstValue("amount"));
    }

    @Nullable
    public String getLnurl() {
        return getFirstTagFirstValue("lnurl");
    }

    public NostrPublicKey getRecipient() {
        return NostrPublicKey.fromHex(getFirstTagFirstValue("p"));
    }

    public NostrPublicKey getSender() {
        return getPubkey();
    }

    public List<String> getRelays() {
        List<TagValue> relays = getTag("relays");
        if (relays.isEmpty()) return List.of();
        return List.copyOf(new ArrayList<>(relays.get(0).getAll()));
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
}
