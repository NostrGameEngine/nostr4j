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

package org.ngengine.blossom4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

public class BlossomAuth {

    private final NostrSigner signer;

    public BlossomAuth(NostrSigner signer) {
        this.signer = signer;
    }

    public AsyncTask<SignedNostrEvent> getAuthEvent(BlossomVerb verb, String msg, Collection<String> hashes) {
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(24242);
        event.withContent(msg);
        event.withTag("t", verb.name().toLowerCase());
        for (String hash : hashes) {
            event.withTag("x", hash);
        }
        event.withExpiration(Instant.now().plusSeconds(20 * 60)); // 20 minutes expiration
        return signer.sign(event);
    }

    public AsyncTask<String> getAuth(BlossomVerb verb, String msg, Collection<String> hashes) {
        return getAuthEvent(verb, msg, hashes)
            .then(ev -> {
                String json = NostrMessage.toJSON(ev);
                return NGEPlatform.get().base64encode(json.getBytes(StandardCharsets.UTF_8));
            });
    }
}
