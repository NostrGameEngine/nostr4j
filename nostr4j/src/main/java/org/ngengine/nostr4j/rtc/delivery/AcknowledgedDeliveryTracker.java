/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.delivery;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;

/**
 * Bounded tracker for transport attempts that require an authenticated
 * destination acknowledgement.
 */
public final class AcknowledgedDeliveryTracker implements AutoCloseable {

    public static final int DEFAULT_MAX_PENDING_DELIVERIES = 4096;

    public static final class PendingDelivery {

        private final long attemptId;
        private final Long packetId;
        private final long createdAtMs;
        private final Consumer<Boolean> resolve;
        private final Consumer<Throwable> reject;
        private volatile AsyncTask<Void> timeoutTask;

        private PendingDelivery(long attemptId, Long packetId, Consumer<Boolean> resolve, Consumer<Throwable> reject) {
            this.attemptId = attemptId;
            this.packetId = packetId;
            this.createdAtMs = System.currentTimeMillis();
            this.resolve = resolve;
            this.reject = reject;
        }

        public long getAttemptId() {
            return attemptId;
        }

        public Long getPacketId() {
            return packetId;
        }

        public long getAgeMs() {
            return Math.max(0L, System.currentTimeMillis() - createdAtMs);
        }
    }

    private final String transportName;
    private final long timeoutMs;
    private final int maxPending;
    private final AsyncExecutor timeoutExecutor;
    private final Consumer<PendingDelivery> timeoutListener;
    private final Map<Long, PendingDelivery> pending = new ConcurrentHashMap<Long, PendingDelivery>();
    private final Object pendingLock = new Object();
    private volatile boolean closed;

    public AcknowledgedDeliveryTracker(
        String transportName,
        long timeoutMs,
        int maxPending,
        AsyncExecutor timeoutExecutor,
        Consumer<PendingDelivery> timeoutListener
    ) {
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("Delivery acknowledgement timeout must be positive");
        }
        if (maxPending <= 0) {
            throw new IllegalArgumentException("Maximum pending deliveries must be positive");
        }
        this.transportName = transportName;
        this.timeoutMs = timeoutMs;
        this.maxPending = maxPending;
        this.timeoutExecutor = timeoutExecutor;
        this.timeoutListener = timeoutListener;
    }

    public PendingDelivery register(long attemptId, Long packetId, Consumer<Boolean> resolve, Consumer<Throwable> reject) {
        if (attemptId == 0L) {
            throw new IllegalArgumentException("Delivery attempt id must be non-zero");
        }
        PendingDelivery delivery = new PendingDelivery(attemptId, packetId, resolve, reject);
        synchronized (pendingLock) {
            if (closed) {
                throw new IllegalStateException("Delivery tracker is closed");
            }
            if (pending.size() >= maxPending) {
                throw new IllegalStateException("Maximum pending acknowledged deliveries exceeded: " + maxPending);
            }
            PendingDelivery existing = pending.putIfAbsent(Long.valueOf(attemptId), delivery);
            if (existing != null) {
                throw new IllegalStateException("Duplicate delivery attempt id: " + attemptId);
            }
            delivery.timeoutTask =
                timeoutExecutor.runLater(
                    () -> {
                        PendingDelivery timedOut = remove(attemptId);
                        if (timedOut != null) {
                            if (timeoutListener != null) {
                                timeoutListener.accept(timedOut);
                            }
                            timedOut.reject.accept(new DeliveryAckTimeoutException(transportName, attemptId, packetId));
                        }
                        return null;
                    },
                    timeoutMs,
                    TimeUnit.MILLISECONDS
                );
        }
        return delivery;
    }

    public PendingDelivery remove(long attemptId) {
        synchronized (pendingLock) {
            PendingDelivery removed = pending.remove(Long.valueOf(attemptId));
            if (removed != null) {
                AsyncTask<Void> timeoutTask = removed.timeoutTask;
                if (timeoutTask != null) {
                    timeoutTask.cancel();
                }
            }
            return removed;
        }
    }

    public PendingDelivery complete(long attemptId) {
        PendingDelivery delivery = remove(attemptId);
        if (delivery == null) {
            return null;
        }
        delivery.resolve.accept(Boolean.TRUE);
        return delivery;
    }

    public boolean resolve(long attemptId, boolean delivered) {
        PendingDelivery delivery = remove(attemptId);
        if (delivery == null) {
            return false;
        }
        delivery.resolve.accept(Boolean.valueOf(delivered));
        return true;
    }

    public boolean fail(long attemptId, Throwable error) {
        PendingDelivery delivery = remove(attemptId);
        if (delivery == null) {
            return false;
        }
        delivery.reject.accept(error == null ? new RuntimeException("Acknowledged delivery failed") : error);
        return true;
    }

    public void failAll(Throwable error) {
        Throwable failure = error == null ? new RuntimeException("Acknowledged delivery failed") : error;
        for (Long attemptId : pending.keySet()) {
            PendingDelivery delivery = remove(attemptId.longValue());
            if (delivery != null) {
                delivery.reject.accept(failure);
            }
        }
    }

    public int size() {
        return pending.size();
    }

    @Override
    public void close() {
        synchronized (pendingLock) {
            closed = true;
        }
        failAll(new IllegalStateException("Delivery tracker is closed"));
    }
}
