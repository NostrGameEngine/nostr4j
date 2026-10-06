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
package org.ngengine.nostr4j.event;

import java.util.Arrays;
import java.util.List;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/** Serializes the fixed NIP-01 hashing structure without a generic object tree. */
final class NostrEventJson {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private NostrEventJson() {}

    static String canonical(String pubkey, NostrEvent event) {
        String content = event.getContent();
        List<List<String>> tags = event.getTagRows();
        long createdAt = event.getCreatedAt().getEpochSecond();
        NGEPlatform platform = NGEUtils.getPlatform();
        if (platform.supportsMinimalJSONEscaping() && createdAt >= -9007199254740991L && createdAt <= 9007199254740991L) {
            // Native JSON.stringify is substantially faster than a compiled
            // Java character loop on TeaVM, with the same NIP-01 escaping.
            return platform.toJSON(Arrays.asList(0, pubkey, createdAt, event.getKind(), tags, content));
        }
        int capacity = Math.addExact(80, (content == null ? 4 : content.length()) + (pubkey == null ? 4 : pubkey.length()));
        for (List<String> row : tags) {
            capacity = Math.addExact(capacity, 3);
            for (String value : row) {
                capacity = Math.addExact(capacity, value == null ? 5 : Math.addExact(value.length(), 3));
            }
        }
        StringBuilder json = new StringBuilder(capacity);
        json.append("[0,");
        appendString(json, pubkey);
        json.append(',').append(createdAt);
        json.append(',').append(event.getKind()).append(",[");
        boolean firstRow = true;
        for (List<String> row : tags) {
            if (!firstRow) json.append(',');
            firstRow = false;
            json.append('[');
            boolean firstValue = true;
            for (String value : row) {
                if (!firstValue) json.append(',');
                firstValue = false;
                appendString(json, value);
            }
            json.append(']');
        }
        json.append("],");
        appendString(json, content);
        return json.append(']').toString();
    }

    private static void appendString(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 31 && c != '"' && c != '\\' && (c < '\uD800' || c > '\uDFFF')) continue;
            if (c >= '\uD800' && c <= '\uDBFF' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next >= '\uDC00' && next <= '\uDFFF') {
                    i++;
                    continue;
                }
            }
            json.append(value, start, i);
            switch (c) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                case '\b':
                    json.append("\\b");
                    break;
                case '\f':
                    json.append("\\f");
                    break;
                default:
                    // Escape isolated UTF-16 surrogates before UTF-8 conversion,
                    // matching JSON.stringify without altering valid Unicode pairs.
                    json
                        .append("\\u")
                        .append(HEX[c >>> 12])
                        .append(HEX[(c >>> 8) & 15])
                        .append(HEX[(c >>> 4) & 15])
                        .append(HEX[c & 15]);
            }
            start = i + 1;
        }
        json.append(value, start, value.length()).append('"');
    }
}
