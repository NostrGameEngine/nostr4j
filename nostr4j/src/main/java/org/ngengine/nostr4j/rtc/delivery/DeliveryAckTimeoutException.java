/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.delivery;

public final class DeliveryAckTimeoutException extends RuntimeException implements RetryableDeliveryFailure {

    private static final long serialVersionUID = 1L;
    private final long attemptId;
    private final Long packetId;

    public DeliveryAckTimeoutException(String transportName, long attemptId, Long packetId) {
        super(
            transportName +
            " delivery ack timeout for attemptId=" +
            attemptId +
            (packetId == null ? "" : ", packetId=" + packetId.longValue())
        );
        this.attemptId = attemptId;
        this.packetId = packetId;
    }

    public long getAttemptId() {
        return attemptId;
    }

    public Long getPacketId() {
        return packetId;
    }
}
