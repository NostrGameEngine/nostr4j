/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.packet;

public enum RoutedFrameType {
    DATA(1),
    CONTROL(2),
    ACK(3),
    BROADCAST(4);

    private final int wireValue;

    RoutedFrameType(int wireValue) {
        this.wireValue = wireValue;
    }

    int wireValue() {
        return wireValue;
    }

    static RoutedFrameType fromWire(int value) {
        for (RoutedFrameType type : values()) {
            if (type.wireValue == value) return type;
        }
        throw new IllegalArgumentException("Unknown routed frame type");
    }
}
