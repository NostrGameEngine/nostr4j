/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.delivery;

/**
 * A routed attempt could not establish or retain a circuit. Retrying the same
 * logical packet after topology repair is safe.
 */
public final class DeliveryRouteUnavailableException extends RuntimeException implements RetryableDeliveryFailure {

    private static final long serialVersionUID = 1L;

    public DeliveryRouteUnavailableException(String message) {
        super(message);
    }

    public DeliveryRouteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
