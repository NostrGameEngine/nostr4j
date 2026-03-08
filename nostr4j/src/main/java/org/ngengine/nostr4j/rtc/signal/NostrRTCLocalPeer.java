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
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * The local peer (ourselves). This is used to store our own information and configuration, such as the signer and TURN/STUN servers.
 * {@inheritDoc}
 */
public final class NostrRTCLocalPeer extends NostrRTCPeer {

    private final NostrSigner signer;
    private final Collection<String> stunServers;
    private static final AtomicLong sessionIdCounter = new AtomicLong(0);

    private static String newSessionId() {
        String sessionId = "nostr4j";
        sessionId += System.currentTimeMillis();
        sessionId += "-" + sessionIdCounter.incrementAndGet();
        sessionId += "-" + NGEUtils.bytesToHex(NGEPlatform.get().randomBytes(16));
        return sessionId;
    }

    public NostrRTCLocalPeer(
        NostrSigner signer,
        Collection<String> stunServers,
        String applicationId,
        String protocolId,
        NostrKeyPair roomKeyPair,
        @Nullable String turnServer
    ) {
        this(signer, stunServers, applicationId, protocolId, newSessionId(), roomKeyPair, turnServer);
    }

    public NostrRTCLocalPeer(
        NostrSigner signer,
        Collection<String> stunServers,
        String applicationId,
        String protocolId,
        String sessionId,
        NostrKeyPair roomKeyPair,
        @Nullable String turnServer
    ) {
        super(
            NGEUtils.awaitNoThrow(signer.getPublicKey()),
            applicationId,
            protocolId,
            sessionId,
            roomKeyPair.getPublicKey(),
            turnServer
        );
        Objects.requireNonNull(signer);
        Objects.requireNonNull(stunServers);
        this.signer = signer;
        this.stunServers = stunServers;
    }

    public NostrSigner getSigner() {
        return signer;
    }

    public Collection<String> getStunServers() {
        return stunServers;
    }

    @Override
    public String toString() {
        return (
            "NostrRTCLocalPeer{" +
            "pubkey=" +
            getPubkey() +
            ", applicationId='" +
            getApplicationId() +
            '\'' +
            ", protocolId='" +
            getProtocolId() +
            '\'' +
            ", sessionId='" +
            getSessionId() +
            '\'' +
            ", turnServer='" +
            getTurnServer() +
            '\'' +
            ", stunServers=" +
            stunServers +
            '}'
        );
    }
}
