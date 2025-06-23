package org.ngengine.nostr4j.ads;

public enum SdanMimeType {
    IMAGE_JPEG("image/jpeg"),
    IMAGE_PNG("image/png"),
    IMAGE_GIF("image/gif"),
    TEXT_PLAIN("text/plain"),
    TEXT_MARKDOWN("text/markdown");

    private final String value;

    SdanMimeType(String value) {
        this.value = value;
    }

    public String toString() {
        return value;
    }
    
    public static SdanMimeType fromString(String value) {
        for (SdanMimeType type : SdanMimeType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MIME type: " + value);
    }
}
