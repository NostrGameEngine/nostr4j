package org.ngengine.nostrads.protocol.types;

/**
 * Enum for supported action types
 */
public enum AdActionType {
    LINK("link"),
    VIEW("view"),
    CONVERSION("conversion"),
    ATTENTION("attention");

    private final String value;

    AdActionType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AdActionType fromValue(String value) {
        for (AdActionType type : AdActionType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown action type: " + value);
    }
}