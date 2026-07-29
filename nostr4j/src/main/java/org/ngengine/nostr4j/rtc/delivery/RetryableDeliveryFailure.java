/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.delivery;

/**
 * Marker for a delivery failure that may be retried without discarding the
 * logical packet at the head of the send queue.
 */
public interface RetryableDeliveryFailure {}
