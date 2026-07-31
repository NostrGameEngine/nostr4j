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
package org.ngengine.nostr4j.rtc.turn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEPlatform;

/** Computes the dc4 directional binding for encrypted TURN data. */
public final class NostrTURNRoutingHash {

    public static final int ROUTING_HASH_SIZE = 32;
    public static final String DOMAIN = "NIP-DC/TURN/DATA/ROUTING-HASH/v1";

    private static final int LENGTH_PREFIX_SIZE = Integer.BYTES;

    private NostrTURNRoutingHash() {}

    public static ByteBuffer compute(
        NostrPublicKey roomPubkey,
        String channelLabel,
        String sourceSessionId,
        String targetSessionId,
        String protocolId,
        String applicationId,
        NostrPublicKey sourcePubkey,
        NostrPublicKey targetPubkey
    ) {
        ByteBuffer[] fields = new ByteBuffer[] {
            utf8(DOMAIN),
            rawKey(roomPubkey, "roomPubkey"),
            utf8(Objects.requireNonNull(channelLabel, "channelLabel")),
            utf8(Objects.requireNonNull(sourceSessionId, "sourceSessionId")),
            utf8(Objects.requireNonNull(targetSessionId, "targetSessionId")),
            utf8(Objects.requireNonNull(protocolId, "protocolId")),
            utf8(Objects.requireNonNull(applicationId, "applicationId")),
            rawKey(sourcePubkey, "sourcePubkey"),
            rawKey(targetPubkey, "targetPubkey"),
        };

        long encodedSize = 0L;
        for (ByteBuffer field : fields) {
            encodedSize += LENGTH_PREFIX_SIZE + (long) field.remaining();
        }
        if (encodedSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("TURN routing context is too large");
        }

        ByteBuffer canonical = NGEPlatform.get().getNativeAllocator().malloc((int) encodedSize).order(ByteOrder.BIG_ENDIAN);
        for (ByteBuffer field : fields) {
            canonical.putInt(field.remaining());
            canonical.put(field.duplicate());
        }
        canonical.flip();

        ByteBuffer hash = NGEPlatform.get().sha256(canonical.asReadOnlyBuffer());
        if (hash.remaining() != ROUTING_HASH_SIZE) {
            throw new IllegalStateException("Platform SHA-256 returned " + hash.remaining() + " bytes");
        }
        return hash.slice().asReadOnlyBuffer();
    }

    private static ByteBuffer utf8(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8)).asReadOnlyBuffer();
    }

    private static ByteBuffer rawKey(NostrPublicKey key, String name) {
        return Objects.requireNonNull(key, name).asReadOnlyBuffer();
    }
}
