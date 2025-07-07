package org.ngengine.nostrads.protocol.types;

/**
 * Enum for supported slot bid values
 */
public enum AdPriceSlot {
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

    AdPriceSlot(String value, long msats) {
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

    public static AdPriceSlot fromValue(long msats) {
        for (int i= AdPriceSlot.values().length-1; i >=0 ; i--) {
            AdPriceSlot type = AdPriceSlot.values()[i];
            if (type.msats <= msats) {
                return type;
            }
        }
        return AdPriceSlot.BTC1_000; // Default to the smallest slot if none match       
    }
    

    public static AdPriceSlot fromString(String value) {
        for (AdPriceSlot type : AdPriceSlot.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MIME type: " + value);
    }
}