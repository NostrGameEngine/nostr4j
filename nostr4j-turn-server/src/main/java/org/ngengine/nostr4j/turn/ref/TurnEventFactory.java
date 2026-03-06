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

package org.ngengine.nostr4j.turn.ref;

import com.google.gson.JsonObject;
import java.time.Instant;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

/**
 * Builds and signs TURN server events with the exact header shape expected by the client.
 *
 * <p>This factory is the only place where server-originated TURN headers are created.
 * Keeping this centralized avoids accidental header drift across call-sites.
 */
final class TurnEventFactory {

    // TURN control/event kind as defined by NIP-DC.
    static final int TURN_KIND = 25051;

    // Server signer used to sign all outbound TURN headers.
    private final NostrKeyPairSigner signer;

    TurnEventFactory(NostrKeyPairSigner signer) {
        this.signer = signer;
    }

    NostrPublicKey getServerPubkey() {
        // Exposed for diagnostics and integration tests.
        return NGEUtils.awaitNoThrow(this.signer.getPublicKey());
    }

    SignedNostrEvent createChallenge(TurnClientConnection connection) {
        // Challenge content contains:
        // - opaque challenge token to bind connect request
        // - difficulty requirement for client PoW
        JsonObject content = new JsonObject();
        content.addProperty("challenge", connection.getChallenge());
        content.addProperty("difficulty", connection.getDifficulty());

        // Expiration is encoded as NIP-40 expiration tag.
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withKind(TURN_KIND)
            .createdAt(Instant.now())
            .withTag("t", "challenge")
            .withExpiration(connection.getChallengeExpiresAt())
            .withContent(TurnJson.toJson(content));

        return sign(event);
    }

    SignedNostrEvent createAck(TurnVirtualSocket socket) {
        // Ack contains only "t=ack" and empty content by protocol definition.
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withKind(TURN_KIND)
            .createdAt(Instant.now())
            .withTag("t", "ack")
            .withContent("");

        return sign(event);
    }

    SignedNostrEvent createDisconnect(TurnVirtualSocket socket, String reason, boolean error) {
        // Disconnect content communicates reason and whether this is an error condition.
        JsonObject content = new JsonObject();
        content.addProperty("reason", TurnJson.safeString(reason));
        content.addProperty("error", error);

        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withKind(TURN_KIND)
            .createdAt(Instant.now())
            .withTag("t", "disconnect")
            .withContent(TurnJson.toJson(content));

        return sign(event);
    }

    SignedNostrEvent sign(UnsignedNostrEvent unsignedEvent) {
        // Synchronous bridge used in server code-paths; failure is treated as fatal.
        SignedNostrEvent signed = NGEUtils.awaitNoThrow(this.signer.sign(unsignedEvent));
        if (signed == null) {
            throw new IllegalStateException("Unable to sign TURN event");
        }
        return signed;
    }
}
