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
package org.ngengine.nostr4j.nip01;

import java.util.List;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

public class Nip01 {

    public static Nip01UserMetadata from(NostrEvent event) {
        return new Nip01UserMetadata(event);
    }

    public static AsyncTask<Nip01UserMetadata> fetch(NostrPool pool, NostrPublicKey pubkey) {
        Nip01UserMetadataFilter filter = new Nip01UserMetadataFilter(pubkey);
        return fetch(pool, filter);
    }

    /**
     * Fetches the user metadata for a given filter.
     * @param pool 
     * @param filter
     * @return an AsyncTask that resolves to the Nip01UserMetadata, or null if no metadata is found.
     */
    public static AsyncTask<Nip01UserMetadata> fetch(NostrPool pool, Nip01UserMetadataFilter filter) {
        return pool
            .fetch(filter)
            .then(evs -> {
                if(evs.size()==0) {
                    return null; // No metadata found
                }
                SignedNostrEvent event = (SignedNostrEvent) evs.get(0);
                try {
                    return new Nip01UserMetadata(event);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    public static AsyncTask<List<AsyncTask<NostrMessageAck>>> update(
        NostrPool pool,
        NostrSigner signer,
        Nip01UserMetadata newMetadata
    ) {
        UnsignedNostrEvent event = newMetadata.toUpdateEvent();
        AsyncTask<SignedNostrEvent> signedP = signer.sign(event);
        return signedP.compose(signed -> pool.publish(signed));
    }
}
