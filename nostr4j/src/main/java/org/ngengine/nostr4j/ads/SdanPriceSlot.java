package org.ngengine.nostr4j.ads;

/**
 * Enum for supported slot bid values
 */
public enum SdanPriceSlot {
    BTC1_000("BTC1_000", 1_000),
    BTC2_000("BTC2_000", 2_000),
    BTC10_000("BTC10_000", 10_000),
    BTC100_000("BTC100_000", 100_000),
    BTC1_000_000("BTC1_000_000", 1_000_000),
    BTC2_000_000("BTC2_000_000", 2_000_000),
    BTC5_000_000("BTC5_000_000", 5_000_000),
    BTC10_000_000("BTC10_000_000", 10_000_000),
    BTC50_000_000("BTC50_000_000", 50_000_000),;

    private final String value;
    private final long msats;

    SdanPriceSlot(String value, long msats) {
        this.value = value;
        this.msats = msats;
    }

    @Override
    public String toString() {
        return value;
    }
    

    public long getValueMsats() {
        return msats;
    }

    public static SdanPriceSlot fromValue(long msats) {
        for (int i= 0; i < SdanPriceSlot.values().length; i++) {
            SdanPriceSlot type = SdanPriceSlot.values()[i];
            if (type.msats <= msats) {
                return type;
            }
        }
        return SdanPriceSlot.BTC1_000; // Default to the smallest slot if none match       
    }
    

    public static SdanPriceSlot fromString(String value) {
        for (SdanPriceSlot type : SdanPriceSlot.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MIME type: " + value);
    }
}