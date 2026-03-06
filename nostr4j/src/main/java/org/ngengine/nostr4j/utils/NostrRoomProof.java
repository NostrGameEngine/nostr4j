package org.ngengine.nostr4j.utils;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class NostrRoomProof {

    private NostrRoomProof() {}

    public static String computeId(
        NostrPublicKey roomPubkey,
        Instant createdAt,
        int kind,
        NostrPublicKey eventPubkey,
        String challenge
    ) {
        Objects.requireNonNull(roomPubkey, "Room pubkey cannot be null");
        Objects.requireNonNull(createdAt, "Created at cannot be null");
        Objects.requireNonNull(eventPubkey, "Event pubkey cannot be null");
        Objects.requireNonNull(challenge, "Challenge cannot be null");
        String json = NGEUtils
            .getPlatform()
            .toJSON(
                Arrays.asList(
                    Integer.valueOf(0),
                    roomPubkey.asHex(),
                    Long.valueOf(createdAt.getEpochSecond()),
                    Integer.valueOf(kind),
                    eventPubkey.asHex(),
                    challenge,
                    ""
                )
            );
        return NGEUtils.getPlatform().sha256(json);
    }

    public static AsyncTask<String> sign(
        NostrKeyPair roomKeyPair,
        Instant createdAt,
        int kind,
        NostrPublicKey eventPubkey,
        String challenge
    ) {
        Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        if (roomKeyPair.getPrivateKey() == null) {
            return NGEPlatform.get().wrapPromise((res, rej) -> rej.accept(new IllegalStateException("Missing room private key")));
        }
        String id = computeId(roomKeyPair.getPublicKey(), createdAt, kind, eventPubkey, challenge);
        return NGEUtils.getPlatform().signAsync(id, roomKeyPair.getPrivateKey()._array());
    }

    public static boolean verify(
        NostrPublicKey roomPubkey,
        Instant createdAt,
        int kind,
        NostrPublicKey eventPubkey,
        String challenge,
        String expectedId,
        String signature
    ) {
        if (roomPubkey == null || createdAt == null || eventPubkey == null || challenge == null || expectedId == null || signature == null) {
            return false;
        }
        String computed = computeId(roomPubkey, createdAt, kind, eventPubkey, challenge);
        if (!computed.equals(expectedId)) {
            return false;
        }
        try {
            return NGEUtils.getPlatform().verify(expectedId, signature, roomPubkey._array());
        } catch (Exception e) {
            return false;
        }
    }
}
