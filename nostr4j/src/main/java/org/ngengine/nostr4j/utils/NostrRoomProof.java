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
            return NGEPlatform
                .get()
                .wrapPromise((res, rej) -> rej.accept(new IllegalStateException("Missing room private key")));
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
        if (
            roomPubkey == null ||
            createdAt == null ||
            eventPubkey == null ||
            challenge == null ||
            expectedId == null ||
            signature == null
        ) {
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
