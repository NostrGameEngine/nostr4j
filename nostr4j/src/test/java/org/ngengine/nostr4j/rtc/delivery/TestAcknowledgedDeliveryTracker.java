/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.delivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.NGEPlatform;

public class TestAcknowledgedDeliveryTracker {

    @Test
    public void testCompletionCancelsTimeoutAndResolvesAttempt() throws Exception {
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("delivery-tracker-complete");
        AcknowledgedDeliveryTracker tracker = new AcknowledgedDeliveryTracker("TEST", 1000L, 4, executor, null);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        try {
            tracker.register(7L, Long.valueOf(42L), delivered -> completed.countDown(), failure::set);
            AcknowledgedDeliveryTracker.PendingDelivery delivery = tracker.complete(7L);

            assertNotNull(delivery);
            assertEquals(42L, delivery.getPacketId().longValue());
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(0, tracker.size());
            assertTrue(failure.get() == null);
        } finally {
            tracker.close();
            executor.close();
        }
    }

    @Test
    public void testTimeoutIsRetryableAndPreservesAttemptMetadata() throws Exception {
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("delivery-tracker-timeout");
        CountDownLatch timedOut = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AcknowledgedDeliveryTracker tracker = new AcknowledgedDeliveryTracker(
            "ROUTED",
            40L,
            4,
            executor,
            ignored -> timedOut.countDown()
        );
        try {
            tracker.register(9L, Long.valueOf(73L), ignored -> fail("timeout must not resolve"), failure::set);

            assertTrue(timedOut.await(2, TimeUnit.SECONDS));
            waitForFailure(failure);
            assertTrue(failure.get() instanceof DeliveryAckTimeoutException);
            DeliveryAckTimeoutException timeout = (DeliveryAckTimeoutException) failure.get();
            assertEquals(9L, timeout.getAttemptId());
            assertEquals(73L, timeout.getPacketId().longValue());
            assertTrue(DeliveryFailures.isRetryable(new RuntimeException(timeout)));
            assertEquals(0, tracker.size());
        } finally {
            tracker.close();
            executor.close();
        }
    }

    @Test
    public void testCapacityBoundAndFailAllCleanup() {
        AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor("delivery-tracker-capacity");
        AcknowledgedDeliveryTracker tracker = new AcknowledgedDeliveryTracker("TEST", 1000L, 1, executor, null);
        AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();
        try {
            tracker.register(1L, null, ignored -> {}, firstFailure::set);
            try {
                tracker.register(2L, null, ignored -> {}, ignored -> {});
                fail("capacity limit must reject another pending delivery");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Maximum pending"));
            }

            DeliveryTransportReplacedException replacement = new DeliveryTransportReplacedException("TURN");
            tracker.failAll(replacement);
            assertEquals(replacement, firstFailure.get());
            assertEquals(0, tracker.size());
            assertTrue(DeliveryFailures.isRetryable(replacement));
            assertFalse(DeliveryFailures.isRetryable(new IllegalArgumentException("hard failure")));
        } finally {
            tracker.close();
            executor.close();
        }
    }

    @Test
    public void testCapacityBoundIsAtomicUnderConcurrentRegistration() throws Exception {
        AsyncExecutor timeoutExecutor = NGEPlatform.get().newAsyncExecutor("delivery-tracker-concurrent-timeout");
        AcknowledgedDeliveryTracker tracker = new AcknowledgedDeliveryTracker("TEST", 5000L, 1, timeoutExecutor, null);
        ExecutorService callers = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(16);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < 16; i++) {
                final long attemptId = i + 1L;
                callers.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        tracker.register(attemptId, null, ignored -> {}, ignored -> {});
                        accepted.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(1, accepted.get());
            assertEquals(15, rejected.get());
            assertEquals(1, tracker.size());
        } finally {
            tracker.close();
            callers.shutdownNow();
            timeoutExecutor.close();
        }
    }

    private static void waitForFailure(AtomicReference<Throwable> failure) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (failure.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertNotNull(failure.get());
    }
}
