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

package org.ngengine.nostr4j.nip29;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.nip53.Nip53;
import org.ngengine.platform.NGEUtils;

/**
 * Build NIP-29 audio and video token endpoints and NIP-98 authorization events.
 * Authorization events are intended for the HTTP endpoint and must not be published to relays.
 */
public final class Nip29LiveKit {

    private Nip29LiveKit() {}

    public static String tokenEndpoint(String relay, String group) {
        String safe = Nip53.relay(relay);
        if (safe.isEmpty() || group == null || group.isBlank() || group.length() > 1024) throw new IllegalArgumentException(
            "Invalid room relay or group"
        );
        URI source = URI.create(safe);
        return (
            (source.getScheme().equals("wss") ? "https" : "http") +
            "://" +
            source.getRawAuthority() +
            "/.well-known/nip29/livekit/" +
            URLEncoder.encode(group, StandardCharsets.UTF_8).replace("+", "%20")
        );
    }

    public static UnsignedNostrEvent authorization(String endpoint, String method) {
        if (Nip53.web(endpoint).isEmpty() || !List.of("GET", "POST").contains(method)) throw new IllegalArgumentException(
            "Invalid room endpoint"
        );
        UnsignedNostrEvent event = new UnsignedNostrEvent().withKind(27235).withTag("u", endpoint).withTag("method", method);
        if (method.equals("POST")) event.withTag("payload", NGEUtils.getPlatform().sha256("{}"));
        return event;
    }
}
