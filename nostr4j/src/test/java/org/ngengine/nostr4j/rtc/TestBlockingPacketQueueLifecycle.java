/**
 * BSD 3-Clause License
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.junit.Test;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ExecutionQueue;

public class TestBlockingPacketQueueLifecycle {

    @Test
    public void finiteLifetimeDoesNotRetryAnOtherwiseValidLongAttempt() throws Exception {
        PendingHandler handler = new PendingHandler();
        try (BlockingPacketQueue<String> queue = queue(handler, 60_000L)) {
            queue.enqueue("first");
            field(BlockingPacketQueue.class, "inFlightSince").setLong(queue, System.currentTimeMillis() - 7000L);
            queue.restartIfStuck(6000L);
            assertEquals("the original authenticated delivery may still arrive", 1, handler.attempts.size());
        }
    }

    @Test
    public void completionAfterTheOriginalDeadlineRejectsExactlyOnce() throws Exception {
        PendingHandler handler = new PendingHandler();
        AtomicInteger resolved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try (BlockingPacketQueue<String> queue = queue(handler, 60_000L)) {
            queue.enqueue("first", ignored -> resolved.incrementAndGet(), error -> rejected.incrementAndGet());
            ageHead(queue, 60_000L);
            handler.attempts.get(0).resolve.accept(true);
            queue.loop();
            assertEquals(0, resolved.get());
            assertEquals(1, rejected.get());
            assertEquals(0, queue.size());
        }
    }

    @Test
    public void removingPendingHeadAllowsNextItemAndIgnoresOldFailure() throws Exception {
        PendingHandler handler = new PendingHandler();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger resolved = new AtomicInteger();
        try (BlockingPacketQueue<String> queue = queue(handler, 60_000L)) {
            queue.enqueue("first");
            assertEquals(1, queue.removeIf("first"::equals));
            queue.enqueue("second", ignored -> resolved.incrementAndGet(), error -> rejected.incrementAndGet());
            assertEquals(2, handler.attempts.size());
            handler.attempts.get(0).reject.accept(new IllegalStateException("retired failure"));
            assertEquals(1, queue.size());
            assertEquals(0, rejected.get());
            handler.attempts.get(1).resolve.accept(true);
            assertEquals(1, resolved.get());
        }
    }

    @Test
    public void clearPendingHeadUnblocksNextItemWithoutWaitingForWatchdog() throws Exception {
        PendingHandler handler = new PendingHandler();
        AtomicInteger resolved = new AtomicInteger();
        try (BlockingPacketQueue<String> queue = queue(handler, 60_000L)) {
            queue.enqueue("first");
            queue.clear();
            queue.enqueue("second", ignored -> resolved.incrementAndGet(), error -> fail(error.toString()));
            assertEquals(2, handler.attempts.size());
            handler.attempts.get(0).resolve.accept(true);
            assertEquals(0, resolved.get());
            handler.attempts.get(1).resolve.accept(true);
            assertEquals(1, resolved.get());
        }
    }

    @Test
    public void unlimitedLifetimeStillUsesTheLegacyStuckWatchdog() throws Exception {
        PendingHandler handler = new PendingHandler();
        AtomicInteger resolved = new AtomicInteger();
        try (BlockingPacketQueue<String> queue = queue(handler, 0L)) {
            queue.enqueue("first", ignored -> resolved.incrementAndGet(), error -> fail(error.toString()));
            field(BlockingPacketQueue.class, "inFlightSince").setLong(queue, System.currentTimeMillis() - 7000L);
            queue.restartIfStuck(6000L);
            assertEquals(2, handler.attempts.size());
            handler.attempts.get(0).resolve.accept(true);
            assertEquals(0, resolved.get());
            handler.attempts.get(1).resolve.accept(true);
            assertEquals(1, resolved.get());
        }
    }

    @Test
    public void handlerMayWaitForAnotherThreadClearingQueue() throws Exception {
        AtomicReference<BlockingPacketQueue<String>> owner = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (
            BlockingPacketQueue<String> queue = queue(
                packet -> {
                    clearFromAnotherThread(owner.get(), failure);
                    return AsyncTask.completed(true);
                },
                60_000L
            )
        ) {
            owner.set(queue);
            queue.enqueue("first");
            assertNull(failure.get());
        }
    }

    @Test
    public void completionMayWaitForAnotherThreadClearingQueue() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (BlockingPacketQueue<String> queue = queue(packet -> AsyncTask.completed(true), 60_000L)) {
            queue.enqueue("first", ignored -> clearFromAnotherThread(queue, failure), error -> fail(error.toString()));
            assertNull(failure.get());
        }
    }

    @Test
    public void closeRejectionMayWaitForAnotherThreadClearingQueue() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (BlockingPacketQueue<String> queue = queue(new PendingHandler(), 60_000L)) {
            queue.enqueue("first", ignored -> fail("closed item resolved"), error -> clearFromAnotherThread(queue, failure));
            queue.close();
            assertNull(failure.get());
        }
    }

    @Test
    public void concurrentRegistrationCannotSkipOrOvertakeTheHead() throws Exception {
        List<String> handled = new CopyOnWriteArrayList<>();
        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch allowRegistration = new CountDownLatch(1);
        AtomicBoolean firstRegistration = new AtomicBoolean(true);
        ExecutionQueue delayed = new ExecutionQueue() {
            @Override
            public <T> AsyncTask<T> enqueue(BiConsumer<Consumer<T>, Consumer<Throwable>> action) {
                if (firstRegistration.compareAndSet(true, false)) {
                    registrationEntered.countDown();
                    try {
                        if (!allowRegistration.await(3, TimeUnit.SECONDS)) throw new AssertionError(
                            "registration release missing"
                        );
                    } catch (InterruptedException error) {
                        throw new AssertionError(error);
                    }
                }
                return super.enqueue(action);
            }
        };
        try (
            BlockingPacketQueue<String> queue = queue(
                packet -> {
                    handled.add(packet);
                    return AsyncTask.completed(true);
                },
                60_000L
            )
        ) {
            Field eq = field(BlockingPacketQueue.class, "executionQueue");
            ((ExecutionQueue) eq.get(queue)).close();
            eq.set(queue, delayed);
            Thread first = new Thread(() -> queue.enqueue("first"), "queue-first-registration");
            Thread second = new Thread(() -> queue.enqueue("second"), "queue-second-registration");
            first.setDaemon(true);
            second.setDaemon(true);
            first.start();
            try {
                assertTrue(registrationEntered.await(2, TimeUnit.SECONDS));
                second.start();
                second.join(1000L);
                assertFalse("enqueue may not wait under the queue monitor", second.isAlive());
                assertTrue("second item cannot overtake a pending registration", handled.isEmpty());
            } finally {
                allowRegistration.countDown();
                first.join(2000L);
                second.join(2000L);
            }
            assertFalse(first.isAlive());
            assertEquals(Arrays.asList("first", "second"), handled);
            assertEquals(0, queue.size());
        }
    }

    private static BlockingPacketQueue<String> queue(BlockingPacketQueue.PacketHandler<String> handler, long lifetime) {
        return new BlockingPacketQueue<>(
            handler,
            Logger.getLogger(TestBlockingPacketQueueLifecycle.class.getName()),
            "test delivery",
            60_000L,
            6000L,
            lifetime
        );
    }

    private static void clearFromAnotherThread(BlockingPacketQueue<?> queue, AtomicReference<Throwable> failure) {
        if (Thread.holdsLock(queue)) failure.compareAndSet(null, new AssertionError("callback holds queue monitor"));
        Thread clearer = new Thread(queue::clear, "queue-callback-clear");
        clearer.setDaemon(true);
        clearer.start();
        try {
            clearer.join(1000L);
            if (clearer.isAlive()) failure.compareAndSet(
                null,
                new AssertionError("callback blocked another thread clearing queue")
            );
        } catch (InterruptedException error) {
            failure.compareAndSet(null, error);
        }
    }

    private static void ageHead(BlockingPacketQueue<?> queue, long age) throws Exception {
        Object head = ((Queue<?>) field(BlockingPacketQueue.class, "queue").get(queue)).peek();
        field(head.getClass(), "enqueuedAtMs").setLong(head, System.currentTimeMillis() - age);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class Attempt {

        Consumer<Boolean> resolve;
        Consumer<Throwable> reject;
        final AsyncTask<Boolean> task = AsyncTask.create((success, failure) -> {
            resolve = success;
            reject = failure;
        });
    }

    private static final class PendingHandler implements BlockingPacketQueue.PacketHandler<String> {

        final List<Attempt> attempts = new ArrayList<>();

        @Override
        public AsyncTask<Boolean> handle(String packet) {
            Attempt attempt = new Attempt();
            attempts.add(attempt);
            return attempt.task;
        }
    }
}
