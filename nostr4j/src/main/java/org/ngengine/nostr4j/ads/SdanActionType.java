package org.ngengine.nostr4j.ads;

/**
 * Enum for supported action types
 */
public enum SdanActionType {
    LINK("link"),
    VIEW("view"),
    CONVERSION("conversion"),
    ATTENTION("attention");

    private final String value;

    SdanActionType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SdanActionType fromValue(String value) {
        for (SdanActionType type : SdanActionType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown action type: " + value);
    }
}