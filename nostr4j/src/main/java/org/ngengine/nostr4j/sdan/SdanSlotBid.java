package org.ngengine.nostr4j.sdan;

/**
 * Enum for supported slot bid values
 */
public enum SdanSlotBid {
    BTC10_000("BTC10_000"),
    BTC100_000("BTC100_000"),
    BTC1_000_000("BTC1_000_000"),
    BTC2_000_000("BTC2_000_000"),
    BTC5_000_000("BTC5_000_000"),
    BTC10_000_000("BTC10_000_000"),
    BTC50_000_000("BTC50_000_000");

    private final String value;

    SdanSlotBid(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}