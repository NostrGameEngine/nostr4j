/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/** Schnorr authentication of immutable broadcast frames, independent of the forwarding route. */
final class BroadcastAuthenticator {

    private static final byte[] DOMAIN = "nip-dc-broadcast-v2".getBytes(StandardCharsets.UTF_8);
    private final NostrKeyPair keys;
    private final ByteBuffer scopeHash;
    private final LinkedHashMap<String, Instant> verified = new LinkedHashMap<String, Instant>();

    BroadcastAuthenticator(NostrKeyPair keys, RoutingScope scope) {
        this.keys = java.util.Objects.requireNonNull(keys);
        this.scopeHash = NGEUtils.getPlatform().sha256(scope.canonicalBytes());
    }

    AsyncTask<BroadcastFrame> sign(BroadcastFrame frame, Instant now) {
        // Freeze the input before asynchronous signing and encode once for all tree edges/repairs.
        ByteBuffer bytes = frame.signingBytes();
        byte[] frozen = new byte[bytes.remaining()];
        bytes.get(frozen);
        String hash = digest(ByteBuffer.wrap(frozen));
        return NGEUtils
            .getPlatform()
            .schnorrSignAsync(hash, keys.getPrivateKey().asReadOnlyBuffer())
            .then(signature -> {
                ByteBuffer signed = ByteBuffer.allocate(frozen.length + BroadcastFrame.SIGNATURE_BYTES);
                signed.put(frozen).put(NGEUtils.hexToBytes(signature)).flip();
                return BroadcastFrame.decode(signed, now);
            });
    }

    synchronized boolean verify(BroadcastFrame frame, NostrPublicKey publicKey, Instant now) {
        if (publicKey == null || !frame.getExpiresAt().isAfter(now)) return false;
        String hash = digest(frame.signingBytes());
        String signature = frame.signature();
        // Cache exact signed bytes AND the current authenticated key, never just a broadcast ID.
        String cacheKey = publicKey.asHex() + hash + signature;
        // Expiry was checked above and is part of the digest. Capacity eviction
        // bounds old entries without scanning the entire cache on every packet.
        if (verified.containsKey(cacheKey)) return true;
        try {
            if (!NGEUtils.getPlatform().schnorrVerify(hash, signature, publicKey.asReadOnlyBuffer())) return false;
        } catch (Exception error) {
            return false;
        }
        verified.put(cacheKey, frame.getExpiresAt());
        while (verified.size() > RoutingLimits.MAX_BROADCAST_TRACKERS) {
            verified.remove(verified.keySet().iterator().next());
        }
        return true;
    }

    private String digest(ByteBuffer unsigned) {
        ByteBuffer preimage = ByteBuffer.allocate(DOMAIN.length + scopeHash.remaining() + unsigned.remaining());
        preimage.put(DOMAIN).put(scopeHash.asReadOnlyBuffer()).put(unsigned.asReadOnlyBuffer()).flip();
        return NGEUtils.bytesToHex(NGEUtils.getPlatform().sha256(preimage));
    }

    synchronized void clear() {
        verified.clear();
    }
}
