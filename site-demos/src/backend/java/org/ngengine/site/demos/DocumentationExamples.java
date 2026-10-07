/* BSD 3-Clause License - Copyright (c) 2026, Riccardo Balbo */
package org.ngengine.site.demos;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.io.NostrRTCPeerConnection;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.pool.ackpolicy.NostrPoolQuorumAckPolicy;
import org.ngengine.nostr4j.rtc.NostrRTCRoom;
import org.ngengine.nostr4j.rtc.NostrTURNPool;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

/** Compile-time examples included directly by the documentation pages. */
final class DocumentationExamples {

    static NostrRTCRoom createRoom(NostrSigner signer, String sharedRoomNsec) {
        // --8<-- [start:rtc-room]
        NostrKeyPair roomKeyPair = new NostrKeyPair(
            NostrPrivateKey.fromBech32(sharedRoomNsec));

        RTCSettings settings = RTCSettings.getDefault("my-app", "chat-v1")
            .withSignalingRelays(List.of("wss://relay.ngengine.org"));

        NostrPool signalingPool = new NostrPool();
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            settings, signer, roomKeyPair, null);

        NostrRTCRoom room = new NostrRTCRoom(
            settings, local, roomKeyPair, signalingPool, null);
        // --8<-- [end:rtc-room]
        return room;
    }

    static NostrRTCRoom createTurnRoom(
        RTCSettings settings, NostrSigner signer, NostrKeyPair roomKeyPair, NostrPool signalingPool
    ) {
        // --8<-- [start:rtc-turn]
        String turnUrl = "wss://turn.example.com/turn";
        NostrTURNPool turnPool = new NostrTURNPool();

        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            settings, signer, roomKeyPair, turnUrl);

        NostrRTCRoom room = new NostrRTCRoom(
            settings, local, roomKeyPair, signalingPool, turnPool);
        // --8<-- [end:rtc-turn]
        return room;
    }

    static void publish(NostrPool pool, SignedNostrEvent signed) throws Exception {
        // --8<-- [start:publish-quorum]
        List<AsyncTask<NostrMessageAck>> relayTasks =
            pool.publish(signed, NostrPoolQuorumAckPolicy.get()).await();
        // --8<-- [end:publish-quorum]
    }

    static void expiration(SignedNostrEvent signed) {
        // --8<-- [start:event-expiration]
        Instant expiresAt = signed.getExpiration();
        boolean expired = signed.isExpired();
        boolean current = signed.isCurrent();
        // --8<-- [end:event-expiration]
    }

    static void stream(
        NostrPrivateKey localPrivateKey, NostrPublicKey remotePublicKey, String connectionId
    ) throws IOException {
        // --8<-- [start:rtc-stream]
        RTCSettings settings = RTCSettings.getDefault("my-app", "byte-stream-v3")
            .withSignalingRelays(List.of("wss://relay.ngengine.org"));

        try (NostrRTCPeerConnection connection = new NostrRTCPeerConnection(
                localPrivateKey, settings, connectionId, null, 4096, 16)) {
            connection.connect(remotePublicKey);

            OutputStream output = connection.getOutputStream();
            output.write("hello".getBytes(StandardCharsets.UTF_8));
            output.close();

            byte[] response = connection.getInputStream().readAllBytes();
            System.out.println(new String(response, StandardCharsets.UTF_8));
        }
        // --8<-- [end:rtc-stream]
    }
}
