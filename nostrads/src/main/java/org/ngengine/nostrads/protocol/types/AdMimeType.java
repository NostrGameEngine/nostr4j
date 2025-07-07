package org.ngengine.nostrads.protocol.types;

public enum AdMimeType {
    IMAGE_JPEG("image/jpeg"),
    IMAGE_PNG("image/png"),
    IMAGE_GIF("image/gif"),
    TEXT_PLAIN("text/plain"),
    TEXT_MARKDOWN("text/markdown");

    private final String value;

    AdMimeType(String value) {
        this.value = value;
    }

    public String toString() {
        return value;
    }
    
    public static AdMimeType fromString(String value) {
        for (AdMimeType type : AdMimeType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MIME type: " + value);
    }
}
