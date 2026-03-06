package org.ngengine.nostr4j.turn.ref;

import java.time.Instant;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

import com.google.gson.JsonObject;

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
