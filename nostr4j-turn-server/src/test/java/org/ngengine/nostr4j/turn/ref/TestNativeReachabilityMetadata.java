/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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

package org.ngengine.nostr4j.turn.ref;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TestNativeReachabilityMetadata {

    private static final String METADATA_RESOURCE =
        "/META-INF/native-image/org.ngengine/nostr4j-turn-server/reachability-metadata.json";

    @Test
    public void testUnmodifiableMapIsRegisteredForChallengeSerialization() throws Exception {
        try (InputStream stream = TestNativeReachabilityMetadata.class.getResourceAsStream(METADATA_RESOURCE)) {
            assertNotNull("Missing native-image reachability metadata", stream);
            JsonObject metadata = JsonParser
                .parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
            JsonArray reflection = metadata.getAsJsonArray("reflection");
            assertNotNull("Missing reflection metadata", reflection);

            boolean registered = false;
            for (JsonElement entry : reflection) {
                JsonElement type = entry.getAsJsonObject().get("type");
                if (type != null && "java.util.Collections$UnmodifiableMap".equals(type.getAsString())) {
                    registered = true;
                    break;
                }
            }

            assertTrue("TURN challenge serialization requires Collections$UnmodifiableMap reflection metadata", registered);
        }
    }
}
