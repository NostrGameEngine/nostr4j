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

package org.ngengine.nostr4j.turn.ref;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class TurnJson {

    // Small helper around Gson to keep parsing/serialization behavior centralized.
    private static final Gson GSON = new Gson();

    private TurnJson() {}

    static JsonObject parseObject(String json) {
        // Empty payload is treated as absent object.
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        // Parsing exceptions bubble to caller so protocol validation can fail fast.
        return GSON.fromJson(json, JsonObject.class);
    }

    static String readString(JsonObject object, String key) {
        // Missing object/key maps to empty string for compact validation checks.
        if (object == null || key == null || !object.has(key)) {
            return "";
        }
        JsonElement element = object.get(key);
        // JSON null maps to empty string.
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            // Values are normalized with trim to avoid accidental whitespace mismatches.
            return safeString(element.getAsString());
        } catch (Exception ignored) {
            // Non-string values are treated as absent for resilient validation branches.
            return "";
        }
    }

    static String toJson(JsonObject object) {
        // Canonical-ish compact JSON output for TURN event content.
        return GSON.toJson(object);
    }

    static String safeString(String value) {
        // Null-safe and whitespace-normalized value conversion used across protocol code.
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
