/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.packet;

public enum EndToEndControlType {
    SETUP_CONFIRMED(1),
    DELIVERY_ACK(2),
    CIRCUIT_ERROR(3),
    BROADCAST_ACK(4);

    private final int wireValue;

    EndToEndControlType(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static EndToEndControlType fromWire(int value) {
        for (EndToEndControlType type : values()) {
            if (type.wireValue == value) return type;
        }
        throw new IllegalArgumentException("Invalid end-to-end control type");
    }
}
