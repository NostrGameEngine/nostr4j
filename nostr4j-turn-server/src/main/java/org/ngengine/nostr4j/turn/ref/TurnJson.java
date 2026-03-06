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
