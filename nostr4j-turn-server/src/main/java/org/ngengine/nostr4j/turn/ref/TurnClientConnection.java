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
