package org.ngengine.nostr4j.sdan;

/**
 * Enum for supported slot sizes
 */
public enum SdanSlotSize {
    SIZE_120x90("120x90"),
    SIZE_180x150("180x150"),
    SIZE_728x90("728x90"),
    SIZE_300x250("300x250"),
    SIZE_720x300("720x300"),
    SIZE_240x400("240x400"),
    SIZE_250x250("250x250"),
    SIZE_300x600("300x600"),
    SIZE_160x600("160x600"),
    SIZE_336x280("336x280");

    private final String value;

    SdanSlotSize(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}