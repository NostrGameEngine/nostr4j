/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * See the LICENSE file in the project root for the full license text.
 */
package org.ngengine.nostr4j.benchmark;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

public final class BenchmarkEventFactory {

    private static final String ID_PADDING = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final NostrPublicKey PUBLIC_KEY = NostrPublicKey.fromHex(
        "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    );

    private BenchmarkEventFactory() {}

    public static SignedNostrEvent event(long timestampSeconds, long namespace, long sequence) {
        String suffix = Long.toUnsignedString(sequence ^ (namespace * 0x9e3779b97f4a7c15L), 16);
        String id = ID_PADDING.substring(suffix.length()) + suffix;
        return new SignedNostrEvent(
            id,
            PUBLIC_KEY,
            1,
            "benchmark-event",
            Instant.ofEpochSecond(timestampSeconds),
            "",
            Collections.<List<String>>emptyList()
        );
    }
}
