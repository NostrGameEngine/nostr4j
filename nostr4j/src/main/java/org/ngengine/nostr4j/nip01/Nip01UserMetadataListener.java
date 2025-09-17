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

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;

public class Nip01UserMetadataListener implements NostrSubEventListener {

    private static final Logger logger = Logger.getLogger(Nip01UserMetadataListener.class.getName());
    private final NostrPublicKey pubkey;
    private Consumer<Nip01UserMetadata> consumer;

    public Nip01UserMetadataListener(NostrPublicKey pubkey, Consumer<Nip01UserMetadata> consumer) {
        this.pubkey = pubkey;
        this.consumer = consumer;
    }

    public Nip01UserMetadataListener(Consumer<Nip01UserMetadata> consumer) {
        this(null, consumer);
    }

    public Nip01UserMetadataListener() {
        this.pubkey = null;
    }

    @Override
    public void onSubEvent(NostrSubscription sub, SignedNostrEvent event, boolean stored) {
        try {
            if (event.getKind() != 0 || (pubkey != null && !pubkey.equals(event.getPubkey()))) return;
            Nip01UserMetadata profile = new Nip01UserMetadata(event);
            if (consumer != null) consumer.accept(profile);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing NIP-24 metadata", e);
            // e.printStackTrace();
        }
    }
}
