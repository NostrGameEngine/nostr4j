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

import java.util.Objects;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * A webRTC offer to connect with a peer, with the peer pubkey, sdp and metadata.
 */
public class NostrRTCOfferSignal extends NostrRTCSignal {

    private static final long serialVersionUID = 2L;
    private final AsyncTask<String> offerString;

    public NostrRTCOfferSignal(NostrSigner localSigner, NostrKeyPair roomKeyPair, NostrRTCPeer peer, String offerString) {
        super(localSigner, "offer", roomKeyPair, peer);
        this.offerString = AsyncTask.completed(Objects.requireNonNull(offerString, "Offer string cannot be null"));
    }

    public NostrRTCOfferSignal(NostrSigner localSigner, NostrKeyPair roomKeyPair, SignedNostrEvent event) {
        super(localSigner, "offer", roomKeyPair, event);
        this.offerString = decrypt(event.getContent(), event.getPubkey());
    }

    public String getOfferString() {
        return NGEUtils.awaitNoThrow(offerString);
    }

    @Override
    public void await() {
        NGEUtils.awaitNoThrow(offerString);
    }

    @Override
    protected final AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        event.withContent(getOfferString());
        return AsyncTask.completed(event);
    }

    @Override
    protected final boolean requireRoomSignature() {
        return true;
    }
}
