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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.NostrRelayInfo;

public class TestNostrRelayInfo {

    @SuppressWarnings("unchecked")
    @Test
    public void testRelayInfoSnapshotsMutableSourceMap() {
        Map<String, Object> source = new HashMap<>();
        source.put("name", "relay-a");
        source.put("language_tags", new ArrayList<>(List.of("en", "it")));
        source.put("countries", new ArrayList<>(List.of("IT")));

        Map<String, Object> limitation = new HashMap<>();
        limitation.put("max_message_length", 1024);
        source.put("limitation", limitation);

        NostrRelayInfo info = new NostrRelayInfo("wss://relay.example", source);

        ((List<String>) source.get("language_tags")).add("fr");
        ((List<String>) source.get("countries")).add("US");
        limitation.put("max_message_length", 2048);
        source.put("name", "relay-b");

        assertEquals("relay-a", info.getName());
        assertEquals(List.of("en", "it"), info.getLanguageTags());
        assertEquals(List.of("IT"), info.getCountries());
        assertEquals(1024, info.getLimitation("max_message_length", 0));

        try {
            ((Map<String, Object>) info.get()).put("name", "mutated");
            fail("Expected relay info map to be immutable");
        } catch (UnsupportedOperationException expected) {}

        try {
            ((List<String>) info.get().get("language_tags")).add("de");
            fail("Expected nested relay info list to be immutable");
        } catch (UnsupportedOperationException expected) {}
    }
}
