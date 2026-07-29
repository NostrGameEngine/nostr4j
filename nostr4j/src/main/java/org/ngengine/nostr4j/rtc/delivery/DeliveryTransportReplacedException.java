/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.delivery;

public final class DeliveryTransportReplacedException extends RuntimeException implements RetryableDeliveryFailure {

    private static final long serialVersionUID = 1L;

    public DeliveryTransportReplacedException(String transportName) {
        super(transportName + " transport changed");
    }
}
