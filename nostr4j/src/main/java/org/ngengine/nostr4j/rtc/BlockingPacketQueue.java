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
package org.ngengine.nostr4j.rtc;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ExecutionQueue;
import org.ngengine.platform.NGEPlatform;

/**
 * Blocking queue for packet-like items.
 * A packet handler returns:
 * - true: packet processed, advance queue
 * - false: queue is blocked, stop and require explicit restart()
 */
public final class BlockingPacketQueue<T> implements AutoCloseable {

    @FunctionalInterface
    public interface PacketHandler<T> {
        AsyncTask<Boolean> handle(T packet);

        default AsyncTask<Boolean> handle(T packet, BooleanSupplier attemptActive) {
            return handle(packet);
        }

        /** A replaced transport may invalidate a live attempt before its item deadline. */
        default boolean isInFlightValid() {
            return true;
        }

        default boolean isReady() {
            return true;
        }

        default boolean shouldPauseOnError(Throwable error) {
            return false;
        }
    }

    private static final class Enqueued<T> {

        final T packet;
        final Consumer<Void> resolve;
        final Consumer<Throwable> reject;
        final long enqueuedAtMs;

        Enqueued(T packet, Consumer<Void> resolve, Consumer<Throwable> reject, long enqueuedAtMs) {
            this.packet = packet;
            this.resolve = resolve;
            this.reject = reject;
            this.enqueuedAtMs = enqueuedAtMs;
        }
    }

    private final Queue<Enqueued<T>> queue;
    private final PacketHandler<T> handler;
    private final Logger logger;
    private final String failureMessage;
    private final long watchdogIntervalMs;
    private final long stuckTimeoutMs;
    private final long queueItemTimeoutMs;
    private final LongSupplier clock;
    private final AsyncExecutor watchdogExecutor;
    private volatile boolean closed = false;
    private volatile ExecutionQueue executionQueue = NGEPlatform.get().newExecutionQueue();
    private volatile long epoch = 0;
    private volatile long inFlightSince = 0;
    private volatile long lastRestartAttempt;
    private volatile boolean pausedForRetry = false;
    // Only execution registrations are serialized. Handlers and completion callbacks
    // must remain independent so reentrant close/enqueue can settle other sends.
    private final Queue<Runnable> registrations = new ArrayDeque<>();
    private final Queue<Runnable> deferred = new ArrayDeque<>();
    private final Queue<Runnable> completions = new ArrayDeque<>();
    private final ThreadLocal<Boolean> runningDeferred = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private boolean registering;

    private static final class Registration {

        boolean submitted;
        final Queue<Runnable> ready = new ArrayDeque<>();
    }

    public BlockingPacketQueue(PacketHandler<T> handler, Logger logger, String failureMessage) {
        this(handler, logger, failureMessage, 1000L, 6000L, 0L);
    }

    public BlockingPacketQueue(
        PacketHandler<T> handler,
        Logger logger,
        String failureMessage,
        long watchdogIntervalMs,
        long stuckTimeoutMs
    ) {
        this(handler, logger, failureMessage, watchdogIntervalMs, stuckTimeoutMs, 0L);
    }

    public BlockingPacketQueue(
        PacketHandler<T> handler,
        Logger logger,
        String failureMessage,
        long watchdogIntervalMs,
        long stuckTimeoutMs,
        long queueItemTimeoutMs
    ) {
        this(
            handler,
            logger,
            failureMessage,
            watchdogIntervalMs,
            stuckTimeoutMs,
            queueItemTimeoutMs,
            System::currentTimeMillis
        );
    }

    BlockingPacketQueue(
        PacketHandler<T> handler,
        Logger logger,
        String failureMessage,
        long watchdogIntervalMs,
        long stuckTimeoutMs,
        long queueItemTimeoutMs,
        LongSupplier clock
    ) {
        @SuppressWarnings("unchecked")
        Queue<Enqueued<T>> createdQueue = (Queue<Enqueued<T>>) (Queue<?>) NGEPlatform.get().newConcurrentQueue(Enqueued.class);
        this.queue = createdQueue;
        this.handler = handler;
        this.logger = logger;
        this.failureMessage = failureMessage;
        this.watchdogIntervalMs = watchdogIntervalMs;
        this.stuckTimeoutMs = stuckTimeoutMs;
        this.queueItemTimeoutMs = Math.max(0L, queueItemTimeoutMs);
        this.clock = clock;
        this.lastRestartAttempt = clock.getAsLong();
        this.watchdogExecutor = NGEPlatform.get().newAsyncExecutor(BlockingPacketQueue.class.getSimpleName() + "-watchdog");
        startWatchdog();
    }

    public void enqueue(T packet, Consumer<Void> resolve, Consumer<Throwable> reject) {
        synchronized (this) {
            if (closed) {
                if (reject != null) completions.add(() -> reject.accept(new IllegalStateException("Queue is closed")));
            } else {
                Enqueued<T> enqueued = new Enqueued<T>(packet, resolve, reject, clock.getAsLong());
                queue.add(enqueued);
                schedule(enqueued);
            }
        }
        drainDeferred();
    }

    public void enqueue(T packet) {
        enqueue(packet, null, null);
    }

    public int size() {
        return queue.size();
    }

    public int removeIf(Predicate<T> predicate) {
        List<Enqueued<T>> matching = new ArrayList<>();
        for (Enqueued<T> enqueued : queue) {
            if (predicate.test(enqueued.packet)) matching.add(enqueued);
        }
        int removed = 0;
        synchronized (this) {
            Enqueued<T> previousHead = queue.peek();
            for (Enqueued<T> enqueued : matching) {
                if (queue.remove(enqueued)) removed++;
            }
            if (previousHead != queue.peek() && executionQueue != null) {
                stopInternal();
                restartInternal();
            }
        }
        drainDeferred();
        return removed;
    }

    /** Called under the queue monitor; actual registration is ordered and lock-free. */
    private void schedule(Enqueued<T> enqueued) {
        ExecutionQueue eq = executionQueue;
        if (eq == null) return;
        long scheduledEpoch = epoch;
        Registration registration = new Registration();
        registrations.add(() -> {
            try {
                eq.enqueue((resolve, reject) -> {
                    boolean submitted;
                    synchronized (this) {
                        submitted = registration.submitted;
                        Runnable start = () -> startAttempt(enqueued, scheduledEpoch, resolve, reject);
                        if (submitted) {
                            deferred.add(start);
                        } else {
                            // A synchronous provider callback still holds its enqueue
                            // monitor. Publish this work only after enqueue returns.
                            registration.ready.add(start);
                        }
                    }
                    if (submitted) drainDeferred();
                });
            } finally {
                synchronized (this) {
                    registration.submitted = true;
                    deferred.addAll(registration.ready);
                    registration.ready.clear();
                }
            }
        });
    }

    private void startAttempt(Enqueued<T> enqueued, long scheduledEpoch, Consumer<Object> resolve, Consumer<Throwable> reject) {
        synchronized (this) {
            if (!ownsAttempt(enqueued, scheduledEpoch) || failHeadIfExpired(clock.getAsLong())) {
                deferred.add(() -> resolve.accept(null));
            } else {
                inFlightSince = clock.getAsLong();
                deferred.add(() -> runAttempt(enqueued, scheduledEpoch, resolve, reject));
            }
        }
        drainDeferred();
    }

    private void runAttempt(Enqueued<T> enqueued, long scheduledEpoch, Consumer<Object> resolve, Consumer<Throwable> reject) {
        synchronized (this) {
            if (!ownsAttempt(enqueued, scheduledEpoch) || failHeadIfExpired(clock.getAsLong())) return;
        }
        try {
            handler
                .handle(enqueued.packet, () -> isAttemptActive(enqueued, scheduledEpoch))
                .then(processed -> {
                    complete(enqueued, scheduledEpoch, processed, null, resolve, reject);
                    return null;
                })
                .catchException(error -> complete(enqueued, scheduledEpoch, false, error, resolve, reject));
        } catch (Throwable error) {
            complete(enqueued, scheduledEpoch, false, error, resolve, reject);
        }
    }

    private boolean isAttemptActive(Enqueued<T> enqueued, long scheduledEpoch) {
        synchronized (this) {
            if (!ownsAttempt(enqueued, scheduledEpoch) || isExpired(enqueued, clock.getAsLong())) return false;
        }
        boolean valid = handler.isInFlightValid();
        synchronized (this) {
            return valid && ownsAttempt(enqueued, scheduledEpoch) && !isExpired(enqueued, clock.getAsLong());
        }
    }

    private boolean ownsAttempt(Enqueued<T> enqueued, long scheduledEpoch) {
        return !closed && epoch == scheduledEpoch && queue.peek() == enqueued;
    }

    private void complete(
        Enqueued<T> enqueued,
        long scheduledEpoch,
        Boolean processed,
        Throwable error,
        Consumer<Object> resolve,
        Consumer<Throwable> reject
    ) {
        boolean valid = handler.isInFlightValid();
        boolean retryable = error != null && handler.shouldPauseOnError(error);
        synchronized (this) {
            if (ownsAttempt(enqueued, scheduledEpoch) && !failHeadIfExpired(clock.getAsLong()) && valid) {
                inFlightSince = 0;
                if (error == null && Boolean.TRUE.equals(processed)) {
                    pausedForRetry = false;
                    queue.remove(enqueued);
                    deferred.add(() -> resolve.accept(null));
                    if (enqueued.resolve != null) completions.add(() -> enqueued.resolve.accept(null));
                } else if (error == null || retryable) {
                    pausedForRetry = true;
                    stopInternal();
                    deferred.add(() -> resolve.accept(null));
                } else {
                    pausedForRetry = false;
                    stopInternal();
                    IllegalStateException failure = new IllegalStateException(failureMessage, error);
                    rejectEnqueuedOnce(enqueued, failure);
                    deferred.add(() -> reject.accept(failure));
                }
            }
        }
        drainDeferred();
    }

    private void rejectEnqueuedOnce(Enqueued<T> enqueued, Throwable error) {
        if (queue.remove(enqueued) && enqueued.reject != null) {
            completions.add(() -> enqueued.reject.accept(error));
        }
    }

    public void restartIfStuck(long timeoutMs) {
        if (closed || queue.isEmpty()) return;
        boolean ready = handler.isReady();
        boolean valid = handler.isInFlightValid();
        synchronized (this) {
            if (!closed && !failHeadIfExpired(clock.getAsLong()) && !queue.isEmpty()) {
                long now = clock.getAsLong();
                if (executionQueue == null) {
                    if (ready && (pausedForRetry || now - lastRestartAttempt > timeoutMs)) {
                        if (!pausedForRetry) deferred.add(() -> logger.warning("Detected likely stuck queue... recovering"));
                        restartInternal();
                    }
                } else if (inFlightSince > 0 && (!valid || (queueItemTimeoutMs <= 0L && now - inFlightSince > timeoutMs))) {
                    // Finite deadlines bound the whole fragment chain. Preserve the
                    // legacy stuck-attempt watchdog for unlimited-lifetime entries.
                    deferred.add(() -> logger.warning("Detected likely stuck packet... recovering"));
                    pausedForRetry = false;
                    stopInternal();
                    restartInternal();
                }
            }
        }
        drainDeferred();
    }

    /** External hook for stuck detection and retry checks. */
    public void loop() {
        if (closed || queue.isEmpty()) return;
        restartIfStuck(stuckTimeoutMs);
        if (executionQueue == null && handler.isReady()) restart();
    }

    public void restart() {
        synchronized (this) {
            if (!closed && !failHeadIfExpired(clock.getAsLong())) restartInternal();
        }
        drainDeferred();
    }

    private void restartInternal() {
        if (closed || executionQueue != null) return;
        lastRestartAttempt = clock.getAsLong();
        pausedForRetry = false;
        executionQueue = NGEPlatform.get().newExecutionQueue();
        for (Enqueued<T> enqueued : queue) schedule(enqueued);
    }

    public void stop() {
        synchronized (this) {
            pausedForRetry = false;
            stopInternal();
        }
        drainDeferred();
    }

    private void stopInternal() {
        epoch++;
        inFlightSince = 0;
        ExecutionQueue retired = executionQueue;
        executionQueue = null;
        if (retired != null) deferred.add(() -> {
            try {
                retired.close();
            } catch (IOException error) {
                logger.log(Level.FINE, "Failed to close queue", error);
            }
        });
    }

    public void clear() {
        synchronized (this) {
            queue.clear();
            // A removed pending head must not hold subsequent entries behind its
            // unresolved execution task, or let its late callback settle them.
            if (executionQueue != null) {
                stopInternal();
                restartInternal();
            }
        }
        drainDeferred();
    }

    private boolean isExpired(Enqueued<T> enqueued, long nowMs) {
        return queueItemTimeoutMs > 0L && nowMs - enqueued.enqueuedAtMs >= queueItemTimeoutMs;
    }

    private boolean failHeadIfExpired(long nowMs) {
        Enqueued<T> head = queue.peek();
        if (head == null || !isExpired(head, nowMs)) return false;
        pausedForRetry = false;
        stopInternal();
        rejectEnqueuedOnce(head, new Exception(failureMessage + " timed out after " + (nowMs - head.enqueuedAtMs) + " ms"));
        if (!queue.isEmpty()) deferred.add(() -> {
            if (handler.isReady()) restart();
        });
        return true;
    }

    private void startWatchdog() {
        watchdogExecutor.runLater(
            () -> {
                if (closed) return null;
                restartIfStuck(stuckTimeoutMs);
                if (!closed) startWatchdog();
                return null;
            },
            watchdogIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    private void drainDeferred() {
        // A per-thread trampoline bounds synchronous provider recursion without
        // preventing another thread from advancing work or completing close().
        if (!runningDeferred.get()) {
            runningDeferred.set(Boolean.TRUE);
            try {
                drainInternal();
            } finally {
                runningDeferred.remove();
            }
        }
        drainCompletions();
    }

    private void drainInternal() {
        while (true) {
            Runnable action;
            boolean registration;
            synchronized (this) {
                registration = !registering && !registrations.isEmpty();
                if (registration) {
                    registering = true;
                    action = registrations.poll();
                } else {
                    action = deferred.poll();
                    if (action == null) return;
                }
            }
            try {
                action.run();
            } catch (Throwable error) {
                logger.log(Level.WARNING, "Packet queue callback failed", error);
            } finally {
                if (registration) {
                    synchronized (this) {
                        registering = false;
                    }
                }
            }
        }
    }

    private void drainCompletions() {
        boolean running = runningDeferred.get();
        // Application callbacks may reenter even on the same thread. They neither
        // own the registration gate nor suppress a nested internal drain.
        runningDeferred.remove();
        try {
            while (true) {
                Runnable completion;
                synchronized (this) {
                    completion = completions.poll();
                }
                if (completion == null) return;
                try {
                    completion.run();
                } catch (Throwable error) {
                    logger.log(Level.WARNING, "Packet queue completion callback failed", error);
                }
            }
        } finally {
            if (running) runningDeferred.set(Boolean.TRUE); else runningDeferred.remove();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!closed) {
                closed = true;
                pausedForRetry = false;
                stopInternal();
                Enqueued<T> enqueued;
                while ((enqueued = queue.poll()) != null) {
                    Enqueued<T> pending = enqueued;
                    if (pending.reject != null) completions.add(() ->
                        pending.reject.accept(new IllegalStateException("Queue is closed"))
                    );
                }
                deferred.add(() -> {
                    try {
                        watchdogExecutor.close();
                    } catch (Exception error) {
                        logger.log(Level.FINE, "Failed to close queue watchdog", error);
                    }
                });
            }
        }
        drainDeferred();
    }
}
