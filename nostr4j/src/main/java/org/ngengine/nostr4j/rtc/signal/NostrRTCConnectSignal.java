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

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

/**
 * Announce the peer can accept connections.
 * (unencrypted)
 */
public final class NostrRTCConnectSignal extends NostrRTCSignal {

    private static final long serialVersionUID = 2L;
    private volatile Instant expireAt;
    private final String message;

    public NostrRTCConnectSignal(
        NostrSigner localSigner,
        NostrKeyPair roomKeyPair,
        NostrRTCPeer peer,
        Instant expireAt,
        @Nullable String message
    ) {
        super(localSigner, "connect", roomKeyPair, peer);
        this.expireAt = Objects.requireNonNull(expireAt, "Expire at cannot be null");
        this.message = message;
    }

    public NostrRTCConnectSignal(NostrSigner localSigner, NostrKeyPair roomKeyPair, SignedNostrEvent event) {
        super(localSigner, "connect", roomKeyPair, event);
        this.expireAt = event.getExpiration();
        this.message = event.getContent();
    }

    public void updateExpireAt(Instant expireAt) {
        this.expireAt = Objects.requireNonNull(expireAt, "Expire at cannot be null");
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expireAt);
    }

    @Override
    protected final AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        event.withTag("expiration", String.valueOf(expireAt.getEpochSecond()));
        if (message != null) {
            event.withContent(message);
        }
        return AsyncTask.completed(event);
    }

    @Override
    protected final boolean requireRoomSignature() {
        return false;
    }
}
