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
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.blossom4j.BlobDescriptor;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;

public class TestOrderedJsonMaps {

    @Test
    public void signedEventHeadersUseExplicitInsertionOrderWithoutChangingTurnVerification() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("tags", List.of(List.of("t", "data")));
        raw.put("sig", "0".repeat(128));
        raw.put("created_at", 1700000000L);
        raw.put("content", "Unicode 🦊");
        raw.put("kind", 25051);
        raw.put("pubkey", "0".repeat(64));
        raw.put("id", "1".repeat(64));
        SignedNostrEvent event = new SignedNostrEvent(raw);
        assertEquals(
            List.of("id", "pubkey", "kind", "content", "created_at", "sig", "tags"),
            new ArrayList<>(event.toMap().keySet())
        );
        byte[] header = NostrTURNCodec.encodeHeader(event);
        var frame = NostrTURNCodec.encodeFrame(header, 71, 1, List.of());
        assertTrue(NostrTURNCodec.compareHeaders(frame, NostrTURNCodec.encodeHeader(NostrTURNCodec.decodeHeader(frame))));
        assertThrows(UnsupportedOperationException.class, () -> event.toMap().put("extra", true));
    }

    @Test
    public void filtersKeepTagInsertionOrderWhenCopiedAndSerialized() {
        NostrFilter filter = new NostrFilter().withKind(1).withTag("z", "last").withTag("a", "first");
        String json = filter.toString();
        assertTrue(json, json.indexOf("#z") < json.indexOf("#a"));
        assertEquals(json, filter.clone().toString());
    }

    @Test
    public void metadataAndBlossomCopiesKeepOrderedFieldsWithoutSharingTheMap() {
        Nip01UserMetadata metadata = new Nip01UserMetadata();
        metadata.metadata.put("last", "z");
        metadata.metadata.put("first", "a");
        assertEquals(List.of("last", "first"), new ArrayList<>(new Nip01UserMetadata(metadata).metadata.keySet()));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("last", "z");
        extra.put("first", "a");
        BlobDescriptor blob = new BlobDescriptor(
            "https://example.test/blob",
            "0".repeat(64),
            1,
            "text/plain",
            Instant.EPOCH,
            extra
        );
        Map<String, Object> copy = blob.toMap();
        assertEquals(List.of("last", "first"), new ArrayList<>(copy.keySet()));
        copy.put("added", true);
        assertFalse(extra.containsKey("added"));
    }
}
