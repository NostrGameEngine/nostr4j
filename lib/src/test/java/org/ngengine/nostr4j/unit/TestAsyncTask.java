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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.jvm.JVMAsyncPlatform;

public class TestAsyncTask {

    private JVMAsyncPlatform platform;

    @Before
    public void setUp() {
        platform = new JVMAsyncPlatform();
    }

    @Test
    public void testBasicPromiseResolution() throws Exception {
        // Create a simple resolved promise
        AsyncTask<String> promise = platform.promisify(
            (resolve, reject) -> {
                resolve.accept("success");
            },
            platform.newPoolExecutor()
        );
        try {
            promise.await();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Test the basic properties
        assertTrue(promise.isDone());
        assertTrue(promise.isSuccess());
        assertFalse(promise.isFailed());
        assertEquals("success", promise.await());
    }

    @Test
    public void testBasicPromiseRejection() {
        // Create a simple rejected promise
        AsyncTask<String> promise = platform.promisify(
            (resolve, reject) -> {
                reject.accept(new RuntimeException("failed"));
            },
            platform.newPoolExecutor()
        );

        try {
            promise.await();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Test the basic properties
        assertTrue(promise.isDone());
        assertFalse(promise.isSuccess());
        assertTrue(promise.isFailed());

        // Verify the exception
        try {
            promise.await();
            fail("Expected exception was not thrown");
        } catch (Exception exception) {
            assertTrue(exception.getCause() instanceof RuntimeException);
            assertTrue(exception.getCause().getMessage().contains("failed"));
        }
    }

    @Test
    public void testAsyncPromiseResolution() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AsyncTask<String>> promiseRef = new AtomicReference<>();

        AsyncTask<String> promise = platform.promisify(
            (resolve, reject) -> {
                new Thread(() -> {
                    try {
                        Thread.sleep(100); // Simulate async operation
                        resolve.accept("async success");
                        latch.countDown();
                    } catch (Exception e) {
                        reject.accept(e);
                    }
                })
                    .start();
            },
            platform.newPoolExecutor()
        );

        promiseRef.set(promise);

        // Wait for async operation to complete
        assertTrue("Async operation timed out", latch.await(5, TimeUnit.SECONDS));

        // Test the properties
        assertTrue(promiseRef.get().isDone());
        assertTrue(promiseRef.get().isSuccess());
        assertFalse(promiseRef.get().isFailed());
        assertEquals("async success", promiseRef.get().await());
    }

    @Test
    public void testSimpleThenChaining() throws Exception {
        AsyncTask<Integer> promise = platform
            .promisify(
                (resolve, reject) -> {
                    resolve.accept(5);
                },
                platform.newPoolExecutor()
            )
            .then(value -> ((Integer) value) * 2);

        assertEquals(Integer.valueOf(10), promise.await());
    }

    @Test
    public void testMultipleThenChaining() throws Exception {
        AsyncTask<Integer> promise = platform
            .promisify(
                (resolve, reject) -> {
                    resolve.accept(5);
                },
                platform.newPoolExecutor()
            )
            .then(value -> Integer.valueOf(((Integer) value) * 2))
            .then(value -> value + 3)
            .then(value -> value * value);

        assertEquals(Integer.valueOf(169), promise.await()); // ((5*2)+3)^2 = 13^2 = 169
    }

    @Test
    public void testErrorPropagationInChain() {
        final List<String> executionPath = new ArrayList<>();

        AsyncTask<String> promise = platform
            .promisify(
                (resolve, reject) -> {
                    executionPath.add("start");
                    resolve.accept("step1");
                },
                platform.newPoolExecutor()
            )
            .then(value -> {
                executionPath.add((String) value);
                return value + "-step2";
            })
            .then(value -> {
                executionPath.add(value);
                throw new RuntimeException("Error in chain");
            })
            .then(value -> {
                executionPath.add("This should not execute");
                return value + "-step4";
            });

        // Check that the chain executed properly until the exception
        try {
            promise.await();
            fail("Expected exception was not thrown");
        } catch (Exception exception) {
            assertTrue(exception.getCause() instanceof RuntimeException);
            assertTrue(exception.getCause().getMessage().contains("Error in chain"));
        }

        // Check the execution path
        assertEquals(3, executionPath.size());
        assertEquals("start", executionPath.get(0));
        assertEquals("step1", executionPath.get(1));
        assertEquals("step1-step2", executionPath.get(2));
    }

    @Test
    public void testExceptionallyHandler() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        AtomicReference<Throwable> capturedError = new AtomicReference<>();

        platform
            .promisify(
                (resolve, reject) -> {
                    reject.accept(new RuntimeException("test error"));
                },
                platform.newPoolExecutor()
            )
            .catchException(error -> {
                handlerCalled.set(true);
                capturedError.set(error);
                latch.countDown();
            });

        // Wait for exceptionally to be called with timeout
        assertTrue("Exceptionally handler was not called", latch.await(5, TimeUnit.SECONDS));

        assertTrue("Handler was not called", handlerCalled.get());
        assertNotNull("Error was not captured", capturedError.get());
        assertEquals("test error", capturedError.get().getMessage());
    }

    @Test
    public void testErrorRecoveryWithExceptionally() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        platform
            .promisify(
                (resolve, reject) -> {
                    reject.accept(new RuntimeException("original error"));
                },
                platform.newPoolExecutor()
            )
            .catchException(error -> {
                // Verify we got the right error
                assertEquals("original error", error.getMessage());
                handlerCalled.set(true);
                latch.countDown();
            });

        // Wait for exceptionally to process with timeout
        assertTrue("Exceptionally handler was not called", latch.await(5, TimeUnit.SECONDS));
        assertTrue("Handler was not called", handlerCalled.get());
    }

    @Test
    public void testComplexChaining() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger(0);
        AtomicReference<Exception> capturedError = new AtomicReference<>();

        AsyncTask<Integer> promise = platform
            .promisify(
                (resolve, reject) -> {
                    resolve.accept(1);
                },
                platform.newPoolExecutor()
            )
            .then(v -> {
                counter.incrementAndGet();
                return ((Integer) v) + 1;
            })
            .then(v -> {
                counter.incrementAndGet();
                if (v == 2) {
                    throw new RuntimeException("Simulated error");
                }
                return v + 1;
            })
            .catchException(e -> {
                counter.incrementAndGet();
                latch.countDown();
            })
            .then(v -> {
                // This won't be called because the previous step failed
                counter.incrementAndGet();
                return 100;
            });

        // Wait for exceptionally to be called
        assertTrue("Exceptionally handler was not called", latch.await(5, TimeUnit.SECONDS));

        // The value should be null because the chain was broken
        try {
            promise.await();
            fail("Expected exception was not thrown");
        } catch (Exception exception) {
            // Expected behavior
            capturedError.set(exception);
        }

        // Check that only the first 3 steps executed (including exceptionally)
        assertEquals("Wrong number of steps executed", 3, counter.get());
        assertNotNull("No exception was thrown", capturedError.get());
    }

    @Test
    public void testConcurrentPromises() throws Exception {
        int promiseCount = 10;
        CountDownLatch latch = new CountDownLatch(promiseCount);
        List<AsyncTask<Integer>> promises = new ArrayList<>();

        for (int i = 0; i < promiseCount; i++) {
            final int index = i;
            AsyncTask<Integer> promise = platform.promisify(
                (resolve, reject) -> {
                    new Thread(() -> {
                        try {
                            Thread.sleep(10 * index);
                            resolve.accept(index);
                            latch.countDown();
                        } catch (Exception e) {
                            reject.accept(e);
                        }
                    })
                        .start();
                },
                platform.newPoolExecutor()
            );
            promises.add(promise);
        }

        // Wait for all promises to complete
        assertTrue("Not all promises completed in time", latch.await(5, TimeUnit.SECONDS));

        // Verify all promises completed successfully
        for (int i = 0; i < promiseCount; i++) {
            assertEquals("Promise " + i + " returned wrong value", Integer.valueOf(i), promises.get(i).await());
        }
    }

    @Test
    public void testPromiseWithDependencies() throws Exception {
        CountDownLatch promise1Latch = new CountDownLatch(1);
        CountDownLatch promise2Latch = new CountDownLatch(1);

        // Create promises that depend on each other
        AsyncTask<Integer> promise1 = platform.promisify(
            (resolve, reject) -> {
                new Thread(() -> {
                    try {
                        Thread.sleep(50);
                        resolve.accept(5);
                        promise1Latch.countDown();
                    } catch (Exception e) {
                        reject.accept(e);
                    }
                })
                    .start();
            },
            platform.newPoolExecutor()
        );

        AsyncTask<Integer> promise2 = platform.promisify(
            (resolve, reject) -> {
                new Thread(() -> {
                    try {
                        Thread.sleep(50);
                        resolve.accept(10);
                        promise2Latch.countDown();
                    } catch (Exception e) {
                        reject.accept(e);
                    }
                })
                    .start();
            },
            platform.newPoolExecutor()
        );

        // Wait for individual promises to resolve
        assertTrue("Promise 1 didn't complete", promise1Latch.await(5, TimeUnit.SECONDS));
        assertTrue("Promise 2 didn't complete", promise2Latch.await(5, TimeUnit.SECONDS));

        // Create a promise that depends on both previous promises
        AsyncTask<Integer> combinedPromise = promise1.then(v1 -> {
            try {
                int v2 = promise2.await();
                return v1 + v2;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // The combined result should be the sum
        assertEquals("Combined promise returned wrong value", Integer.valueOf(15), combinedPromise.await());
    }

    @Test
    public void testWaitAllSuccess() throws Exception {
        // Create a list of promises that all resolve successfully
        List<AsyncTask<Integer>> promises = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            final int value = i;
            promises.add(
                platform.promisify(
                    (resolve, reject) -> {
                        new Thread(() -> {
                            try {
                                // Different delays to test ordering
                                Thread.sleep(50 * (5 - value));
                                resolve.accept(value);
                            } catch (Exception e) {
                                reject.accept(e);
                            }
                        })
                            .start();
                    },
                    platform.newPoolExecutor()
                )
            );
        }

        // Wait for all promises to complete
        AsyncTask<List<Integer>> combinedPromise = platform.awaitAll(promises);

        // Verify the result
        List<Integer> results = combinedPromise.await();

        // Check size
        assertEquals("Result list should have same size as input list", 5, results.size());

        // Check each value is in the expected order
        for (int i = 0; i < 5; i++) {
            assertEquals("Result should match input promise index", Integer.valueOf(i), results.get(i));
        }
    }

    @Test
    public void testWaitAllEmptyList() throws Exception {
        // Test with an empty list
        List<AsyncTask<String>> emptyList = new ArrayList<>();
        AsyncTask<List<String>> emptyPromise = platform.awaitAll(emptyList);

        // Should resolve immediately with empty list
        List<String> result = emptyPromise.await();
        assertNotNull("Result should not be null", result);
        assertTrue("Result should be empty", result.isEmpty());
    }

    @Test
    public void testWaitAllWithFailure() throws Exception {
        // Create a list with one failing promise
        List<AsyncTask<String>> promises = new ArrayList<>();
        CountDownLatch failureLatch = new CountDownLatch(1);
        AtomicBoolean failureHandled = new AtomicBoolean(false);

        // Add a successful promise
        promises.add(
            platform.promisify(
                (resolve, reject) -> {
                    resolve.accept("success");
                },
                platform.newPoolExecutor()
            )
        );

        // Add a failing promise
        promises.add(
            platform.promisify(
                (resolve, reject) -> {
                    new Thread(() -> {
                        try {
                            Thread.sleep(50);
                            reject.accept(new RuntimeException("Deliberate failure"));
                        } catch (Exception e) {
                            reject.accept(e);
                        }
                    })
                        .start();
                },
                platform.newPoolExecutor()
            )
        );

        // Add another successful promise
        promises.add(
            platform.promisify(
                (resolve, reject) -> {
                    resolve.accept("another success");
                },
                platform.newPoolExecutor()
            )
        );

        // Wait for all promises
        AsyncTask<List<String>> combinedPromise = platform.awaitAll(promises);

        // Add error handler
        combinedPromise.catchException(error -> {
            failureHandled.set(true);
            failureLatch.countDown();
        });

        // Wait for failure to be handled
        assertTrue("Failure handler was not called", failureLatch.await(5, TimeUnit.SECONDS));
        assertTrue("Failure should be handled", failureHandled.get());

        // Verify the combined promise fails
        try {
            combinedPromise.await();
            fail("Should have thrown exception");
        } catch (Exception e) {
            assertTrue("Should contain the right error message", e.getCause().getMessage().contains("Deliberate failure"));
        }
    }

    @Test
    public void testWaitAllOrdering() throws Exception {
        // Create promises that resolve in reverse order but should maintain original
        // order
        List<AsyncTask<String>> promises = new ArrayList<>();
        String[] letters = { "A", "B", "C", "D", "E" };

        for (int i = 0; i < letters.length; i++) {
            final String letter = letters[i];
            final int delay = (letters.length - i) * 50; // E completes first, A last

            promises.add(
                platform.promisify(
                    (resolve, reject) -> {
                        new Thread(() -> {
                            try {
                                Thread.sleep(delay);
                                resolve.accept(letter);
                            } catch (Exception e) {
                                reject.accept(e);
                            }
                        })
                            .start();
                    },
                    platform.newPoolExecutor()
                )
            );
        }

        // Wait for all promises
        AsyncTask<List<String>> combinedPromise = platform.awaitAll(promises);
        List<String> results = combinedPromise.await();

        // Check results match original order regardless of completion order
        assertArrayEquals("Results should be in original order", letters, results.toArray(new String[0]));
    }

    @Test
    public void testWaitAllCompletionTracking() throws Exception {
        // Test that waitAll correctly tracks when all promises are done
        int promiseCount = 10;
        CountDownLatch allResolved = new CountDownLatch(1);
        AtomicReference<List<Integer>> resultRef = new AtomicReference<>();

        List<AsyncTask<Integer>> promises = new ArrayList<>();

        for (int i = 0; i < promiseCount; i++) {
            final int value = i;
            promises.add(
                platform.promisify(
                    (resolve, reject) -> {
                        new Thread(() -> {
                            try {
                                // Random delay between 10-100ms
                                Thread.sleep(10 + (int) (Math.random() * 90));
                                resolve.accept(value);
                            } catch (Exception e) {
                                reject.accept(e);
                            }
                        })
                            .start();
                    },
                    platform.newPoolExecutor()
                )
            );
        }

        // Use then to capture when all promises complete
        platform
            .awaitAll(promises)
            .then(results -> {
                resultRef.set(results);
                allResolved.countDown();
                return results;
            });

        // Wait for completion
        assertTrue("Not all promises completed in time", allResolved.await(5, TimeUnit.SECONDS));

        // Verify all results are present
        List<Integer> results = resultRef.get();
        assertEquals("Should have all results", promiseCount, results.size());

        // Verify each expected value is in the result at the right position
        for (int i = 0; i < promiseCount; i++) {
            assertEquals("Value at index " + i + " should match", Integer.valueOf(i), results.get(i));
        }
    }
}
