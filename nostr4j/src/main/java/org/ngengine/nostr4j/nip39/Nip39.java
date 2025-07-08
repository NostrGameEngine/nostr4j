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
package org.ngengine.nostr4j.nip39;

import java.util.List;
import java.util.regex.Pattern;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;
import org.ngengine.nostr4j.nip01.Nip01UserMetadataFilter;
import org.ngengine.nostr4j.nip24.Nip24ExtraMetadata;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

public class Nip39 {

    private static final Pattern PLATFORM_PATTERN = Pattern.compile("^[a-z0-9._\\-/]*$");

    public static Nip39ExternalIdentities from(NostrEvent event) {
        return new Nip39ExternalIdentities(event);
    }

    public static Nip39ExternalIdentities from(Nip01UserMetadata nip01) {
        return new Nip39ExternalIdentities(nip01);
    }

    public static AsyncTask<Nip39ExternalIdentities> fetch(NostrPool pool, NostrPublicKey pubkey) {
        Nip01UserMetadataFilter filter = new Nip01UserMetadataFilter(pubkey);
        return fetch(pool, filter);
    }

    public static AsyncTask<Nip39ExternalIdentities> fetch(NostrPool pool, Nip01UserMetadataFilter filter) {
        return Nip01
            .fetch(pool, filter)
            .then(v -> {
                return from(v);
            });
    }

    public static AsyncTask<List<AsyncTask<NostrMessageAck>>> update(
        NostrPool pool,
        NostrSigner signer,
        Nip24ExtraMetadata newMetadata
    ) {
        return Nip01.update(pool, signer, newMetadata);
    }

    public static boolean isValidPlatform(String platform) {
        if (platform == null || platform.isEmpty()) {
            return false;
        }
        if (platform.length() > 32) {
            return false;
        }
        // a-z, 0-9 and the characters ._-/ and MUST NOT include :
        if (!PLATFORM_PATTERN.matcher(platform).matches()) {
            return false;
        }
        return true;
    }
}
