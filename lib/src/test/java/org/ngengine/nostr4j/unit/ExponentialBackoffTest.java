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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.ngengine.nostr4j.utils.ExponentialBackoff;

public class ExponentialBackoffTest {

    @Test
    public void testConstructorValidation() {
        // Test valid construction with cooldown
        new ExponentialBackoff(1, 60, 30, TimeUnit.SECONDS, 2.0f);

        // Test invalid parameters
        try {
            new ExponentialBackoff(0, 60, 30, TimeUnit.SECONDS, 2.0f);
            fail("Should throw exception for initialDelay <= 0");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            new ExponentialBackoff(60, 30, 30, TimeUnit.SECONDS, 2.0f);
            fail("Should throw exception for maxDelay < initialDelay");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            new ExponentialBackoff(1, 60, 30, TimeUnit.SECONDS, 1.0f);
            fail("Should throw exception for multiplier <= 1.0");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        try {
            new ExponentialBackoff(1, 60, 0, TimeUnit.SECONDS, 2.0f);
            fail("Should throw exception for cooldown <= 0");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    public void testExponentialIncrease() {
        ExponentialBackoff backoff = new ExponentialBackoff(
            1,
            1000,
            30,
            TimeUnit.SECONDS,
            2.0f
        );

        // Initial state
        long now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        assertEquals(0, backoff.getNextAttemptTime(now, TimeUnit.SECONDS));

        // First failure - should be at current time + 1 second
        backoff.registerFailure();
        now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        long firstDelay = backoff.getNextAttemptTime(now, TimeUnit.SECONDS);
        assertTrue(
            "First delay should be approximately 1 second",
            Math.abs(firstDelay - 1) <= 1
        );

        // Second failure - should double to 2 seconds
        backoff.registerFailure();
        now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        long secondDelay = backoff.getNextAttemptTime(now, TimeUnit.SECONDS);
        assertTrue(
            "Second delay should be approximately 2 seconds",
            Math.abs(secondDelay - 2) <= 1
        );

        // Third failure - should double to 4 seconds
        backoff.registerFailure();
        now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        long thirdDelay = backoff.getNextAttemptTime(now, TimeUnit.SECONDS);
        assertTrue(
            "Third delay should be approximately 4 seconds",
            Math.abs(thirdDelay - 4) <= 1
        );
    }

    @Test
    public void testMaxDelayLimit() {
        // Set max delay to 4 seconds
        ExponentialBackoff backoff = new ExponentialBackoff(
            1,
            4,
            30,
            TimeUnit.SECONDS,
            2.0f
        );

        // Cause multiple failures to exceed max delay
        backoff.registerFailure(); // 1 second
        backoff.registerFailure(); // 2 seconds
        backoff.registerFailure(); // 4 seconds (at max)
        backoff.registerFailure(); // Should still be 4 seconds

        long now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        long delay = backoff.getNextAttemptTime(now, TimeUnit.SECONDS);

        assertTrue("Delay should be capped at max (4 seconds)", delay <= 4);
    }

    @Test
    public void testCooldownDelay() {
        // Use a very short cooldown period for testing (2 seconds)
        ExponentialBackoff backoff = new ExponentialBackoff(
            1,
            100,
            2,
            TimeUnit.SECONDS,
            2.0f
        );

        backoff.registerFailure(); //1
        backoff.registerFailure(); // 2
        backoff.registerSuccess();
        backoff.registerFailure(); // 4
        long now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        long delay = backoff.getNextAttemptTime(now, TimeUnit.SECONDS);
        assertTrue("After success, delay should be 4 seconds", delay == 4);

        backoff.registerFailure();
        now =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() + 6000);
        delay = backoff.getNextAttemptTime(now, TimeUnit.SECONDS);
        assertTrue(
            "After cooldown period, delay should reset to initial (1 second)",
            Math.abs(delay - 1) <= 1
        );
    }

    @Test
    public void testDifferentTimeUnits() {
        ExponentialBackoff backoff = new ExponentialBackoff(
            1000,
            10000,
            30000,
            TimeUnit.MILLISECONDS,
            2.0f
        );

        backoff.registerFailure();

        // Test different output units
        long nowMs = System.currentTimeMillis();
        assertEquals(
            1,
            backoff.getNextAttemptTime(
                TimeUnit.MILLISECONDS.toSeconds(nowMs),
                TimeUnit.SECONDS
            )
        );
        assertEquals(
            1000,
            backoff.getNextAttemptTime(nowMs, TimeUnit.MILLISECONDS)
        );
        assertEquals(
            1000000,
            backoff.getNextAttemptTime(
                TimeUnit.MILLISECONDS.toMicros(nowMs),
                TimeUnit.MICROSECONDS
            )
        );
    }

    @Test
    public void testTimeCalculationEdgeCases() {
        ExponentialBackoff backoff = new ExponentialBackoff(
            60,
            3600,
            300,
            TimeUnit.SECONDS,
            2.0f
        );

        // Test with past time (should return 0)
        backoff.registerFailure();
        long futureTime =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) + 120;
        assertEquals(
            0,
            backoff.getNextAttemptTime(futureTime, TimeUnit.SECONDS)
        );

        // Test with future time
        backoff.registerFailure();
        long pastTime =
            TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - 3600;
        assertTrue(backoff.getNextAttemptTime(pastTime, TimeUnit.SECONDS) > 0);
    }

    @Test
    public void testThreadSafety() throws InterruptedException {
        final int THREAD_COUNT = 20;
        final int ITERATIONS = 50;
        final ExponentialBackoff backoff = new ExponentialBackoff(
            1,
            60,
            5,
            TimeUnit.SECONDS,
            2.0f
        );
        final CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        final AtomicBoolean failed = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Synchronize threads to start at the same time
                    barrier.await();

                    for (int j = 0; j < ITERATIONS; j++) {
                        if (threadId % 3 == 0) {
                            // One third of threads call registerFailure
                            backoff.registerFailure();
                        } else if (threadId % 3 == 1) {
                            // One third call registerSuccess
                            backoff.registerSuccess();
                        } else {
                            // One third read values
                            long now = TimeUnit.MILLISECONDS.toSeconds(
                                System.currentTimeMillis()
                            );
                            backoff.getNextAttemptTime(now, TimeUnit.SECONDS);
                        }

                        // Small delay to increase chance of race conditions
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

        // Wait for all threads to finish
        assertTrue(
            "Threads didn't complete in time",
            latch.await(30, TimeUnit.SECONDS)
        );
        executor.shutdown();

        assertFalse(
            "Exception occurred during concurrent execution",
            failed.get()
        );
    }
}
