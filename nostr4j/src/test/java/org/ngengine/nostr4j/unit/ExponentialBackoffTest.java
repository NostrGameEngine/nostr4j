/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.ngengine.nostr4j.utils.ExponentialBackoff;

public class ExponentialBackoffTest {

    private static void assertDurationEquals(Duration expected, Duration actual) {
        assertEquals("Durations differ", expected, actual);
    }

    private static void assertDurationApprox(Duration expected, Duration actual, Duration tolerance) {
        long diff = Math.abs(expected.toNanos() - actual.toNanos());
        assertTrue("Duration " + actual + " not within ±" + tolerance + " of " + expected, diff <= tolerance.toNanos());
    }

    @Test
    public void testConstructorValidation() {
        // Valid construction
        new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(30), 2.0f);

        // initialDelay <= 0
        try {
            new ExponentialBackoff(Duration.ZERO, Duration.ofSeconds(60), Duration.ofSeconds(30), 2.0f);
            fail("Should throw exception for initialDelay <= 0");
        } catch (IllegalArgumentException expected) {
        }

        // maxDelay < initialDelay
        try {
            new ExponentialBackoff(Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(30), 2.0f);
            fail("Should throw exception for maxDelay < initialDelay");
        } catch (IllegalArgumentException expected) {
        }

        // multiplier <= 1.0
        try {
            new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(30), 1.0f);
            fail("Should throw exception for multiplier <= 1.0");
        } catch (IllegalArgumentException expected) {
        }

        // cooldown <= 0
        try {
            new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ZERO, 2.0f);
            fail("Should throw exception for cooldown <= 0");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testExponentialIncrease() {
        ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(1000),
                Duration.ofSeconds(30), 2.0f);

        Instant t0 = Instant.EPOCH;

        // Initial state: no wait
        assertDurationEquals(Duration.ZERO, backoff.getDelay(t0));

        // First failure -> delay 1s
        backoff.registerFailure(t0);
        assertDurationEquals(Duration.ofSeconds(1), backoff.getDelay(t0));

        // Second failure -> delay 2s
        backoff.registerFailure(t0);
        assertDurationEquals(Duration.ofSeconds(2), backoff.getDelay(t0));

        // Third failure -> delay 4s
        backoff.registerFailure(t0);
        assertDurationEquals(Duration.ofSeconds(4), backoff.getDelay(t0));
    }

    @Test
    public void testMaxDelayLimit() {
        ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(4),
                Duration.ofSeconds(30), 2.0f);

        Instant t0 = Instant.EPOCH;

        backoff.registerFailure(t0); // 1s
        backoff.registerFailure(t0); // 2s
        backoff.registerFailure(t0); // 4s (max)
        backoff.registerFailure(t0); // still 4s

        assertDurationEquals(Duration.ofSeconds(4), backoff.getDelay(t0));
    }

    @Test
    public void testCooldownImmediateFailureKeepsCurrentDelay() {
        // Immediate failure after success should keep the current delay (no reset yet)
        ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(100),
                Duration.ofSeconds(2), 2.0f);

        Instant t0 = Instant.EPOCH;

        backoff.registerFailure(t0); // 1
        backoff.registerFailure(t0); // 2 (currentDelay becomes 4 for next)
        backoff.registerSuccess(t0); // start cooldown window
        backoff.registerFailure(t0); // schedule using currentDelay=4

        assertDurationEquals(Duration.ofSeconds(4), backoff.getDelay(t0));
    }

    @Test
    public void testCooldownResetAfterQuietPeriod() {
        // If cooldown elapses without failures, the delay resets to initial on the next
        // scheduling
        ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(100),
                Duration.ofSeconds(2), 2.0f);

        Instant t0 = Instant.EPOCH;

        backoff.registerFailure(t0); // 1
        backoff.registerFailure(t0); // 2 (currentDelay becomes 4 for next)
        backoff.registerSuccess(t0); // start cooldown window

        // Advance time beyond cooldown without failures to trigger reset logic
        Instant t1 = t0.plusSeconds(6);

        // Calling getDelay with t1 resets internal delay back to initial
        assertDurationEquals(Duration.ZERO, backoff.getDelay(t1));

        // Next failure at t1 should use the initial delay (1s)
        backoff.registerFailure(t1);
        assertDurationEquals(Duration.ofSeconds(1), backoff.getDelay(t1));
    }

    @Test
    public void testTimeCalculationEdgeCases() {
        ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofSeconds(60), Duration.ofSeconds(3600),
                Duration.ofSeconds(300), 2.0f);

        Instant base = Instant.EPOCH;

        // After scheduling, asking far in the future returns zero
        backoff.registerFailure(base);
        Instant future = base.plusSeconds(120);
        assertDurationEquals(Duration.ZERO, backoff.getDelay(future));

        // Asking in the past returns positive delay (since nextAttemptAt > past)
        backoff.registerFailure(base);
        Instant past = base.minusSeconds(3600);
        assertTrue(backoff.getDelay(past).compareTo(Duration.ZERO) > 0);
    }

    @Test
    public void testDurationValues() {
        ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofMillis(500), Duration.ofSeconds(10),
                Duration.ofSeconds(5), 2.0f);

        Instant t0 = Instant.EPOCH;

        backoff.registerFailure(t0);
        Duration d = backoff.getDelay(t0);

        assertDurationEquals(Duration.ofMillis(500), d);
        assertEquals(500, d.toMillis());
        assertDurationApprox(Duration.ofNanos(500_000_000L), d, Duration.ofNanos(0));
    }

    @Test
    public void testThreadSafety() throws InterruptedException {
        final int THREAD_COUNT = 20;
        final int ITERATIONS = 50;
        final ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60),
                Duration.ofSeconds(5), 2.0f);
        final CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        final AtomicBoolean failed = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    barrier.await();

                    for (int j = 0; j < ITERATIONS; j++) {
                        Instant now = Instant.now();
                        if (threadId % 3 == 0) {
                            backoff.registerFailure(now);
                        } else if (threadId % 3 == 1) {
                            backoff.registerSuccess(now);
                        } else {
                            backoff.getDelay(now);
                        }
                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    failed.set(true);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue("Threads didn't complete in time", latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertFalse("Exception occurred during concurrent execution", failed.get());
    }
}