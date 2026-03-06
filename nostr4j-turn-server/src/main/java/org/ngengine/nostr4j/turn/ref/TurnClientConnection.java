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

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.websocket.api.Session;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;

/**
 * A remote peer connected to this server
 * Can have multiple virtual sockets multiplexed on the same websocket
 * connection.
 *
 * <p>This object is strictly per websocket transport connection:
 * - one challenge token per websocket
 * - one accepted-socket map per websocket
 * - socket id collisions checked only inside this websocket scope
 */
final class TurnClientConnection {

    // Underlying websocket session.
    private final Session wsSession;
    // Required PoW difficulty communicated in challenge and enforced at connect.
    private final int difficulty;
    // Challenge expiry instant; connect attempts after this are rejected.
    private final Instant challengeExpiresAt;
    // Opaque challenge token that the client must echo in connect content.
    private final String challenge;
    // Active accepted virtual sockets for this websocket keyed by VSOCKET_ID.
    private final Map<Long, TurnVirtualSocket> sockets = new ConcurrentHashMap<Long, TurnVirtualSocket>();

    TurnClientConnection(Session wsSession, int difficulty, int challengeTtlSeconds) {
        // Websocket attachment for this logical client.
        this.wsSession = wsSession;
        // Challenge difficulty configuration (global server policy for now).
        this.difficulty = difficulty;
        // Challenge lifetime starts at websocket open.
        this.challengeExpiresAt = Instant.now().plusSeconds(challengeTtlSeconds);
        // Random token used as anti-replay handshake binder.
        this.challenge = NostrPrivateKey.generate().asHex();
    }

    Session getWsSession() {
        return wsSession;
    }

    int getDifficulty() {
        return difficulty;
    }

    Instant getChallengeExpiresAt() {
        return challengeExpiresAt;
    }

    String getChallenge() {
        return challenge;
    }

    Map<Long, TurnVirtualSocket> getSockets() {
        return sockets;
    }
}
