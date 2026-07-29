/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.delivery;

public final class DeliveryFailures {

    private DeliveryFailures() {}

    public static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RetryableDeliveryFailure) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
