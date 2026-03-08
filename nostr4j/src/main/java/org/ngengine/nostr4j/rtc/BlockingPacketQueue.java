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
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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

        default boolean isReady() {
            return true;
        }
    }

    private static final class Enqueued<T> {

        final T packet;
        final Consumer<Void> resolve;
        final Consumer<Throwable> reject;

        Enqueued(T packet, Consumer<Void> resolve, Consumer<Throwable> reject) {
            this.packet = packet;
            this.resolve = resolve;
            this.reject = reject;
        }
    }

    private final Queue<Enqueued<T>> queue = new ConcurrentLinkedQueue<Enqueued<T>>();
    private final PacketHandler<T> handler;
    private final Logger logger;
    private final String failureMessage;
    private final long watchdogIntervalMs;
    private final long stuckTimeoutMs;
    private final AsyncExecutor watchdogExecutor;
    private volatile boolean closed = false;
    private volatile ExecutionQueue executionQueue = NGEPlatform.get().newExecutionQueue();
    private volatile long epoch = 0;
    private volatile long inFlightSince = 0;
    private volatile long lastRestartAttempt = System.currentTimeMillis();

    public BlockingPacketQueue(PacketHandler<T> handler, Logger logger, String failureMessage) {
        this(handler, logger, failureMessage, 1000L, 6000L);
    }

    public BlockingPacketQueue(
        PacketHandler<T> handler,
        Logger logger,
        String failureMessage,
        long watchdogIntervalMs,
        long stuckTimeoutMs
    ) {
        this.handler = handler;
        this.logger = logger;
        this.failureMessage = failureMessage;
        this.watchdogIntervalMs = watchdogIntervalMs;
        this.stuckTimeoutMs = stuckTimeoutMs;
        this.watchdogExecutor = NGEPlatform.get().newAsyncExecutor(BlockingPacketQueue.class.getSimpleName() + "-watchdog");
        startWatchdog();
    }

    public void enqueue(T packet, Consumer<Void> resolve, Consumer<Throwable> reject) {
        if (closed) {
            if (reject != null) {
                reject.accept(new IllegalStateException("Queue is closed"));
            }
            return;
        }
        Enqueued<T> enqueued = new Enqueued<T>(packet, resolve, reject);
        queue.add(enqueued);
        schedule(enqueued);
    }

    public void enqueue(T packet) {
        enqueue(packet, null, null);
    }

    public int size() {
        return queue.size();
    }

    public int removeIf(Predicate<T> predicate) {
        int removed = 0;
        for (Enqueued<T> enqueued : queue) {
            if (predicate.test(enqueued.packet) && queue.remove(enqueued)) {
                removed++;
            }
        }
        return removed;
    }

    private void schedule(Enqueued<T> enqueued) {
        ExecutionQueue eq = this.executionQueue;
        if (eq == null) {
            return;
        }
        long scheduledEpoch = this.epoch;
        eq.enqueue((resolve, reject) -> {
            if (this.epoch != scheduledEpoch) {
                reject.accept(new IllegalStateException("A newer queue epoch cancelled this task"));
                return;
            }
            inFlightSince = System.currentTimeMillis();
            handler
                .handle(enqueued.packet)
                .then(processed -> {
                    inFlightSince = 0;
                    if (Boolean.TRUE.equals(processed)) {
                        popHead();
                        resolve.accept(null);
                        if (enqueued.resolve != null) {
                            enqueued.resolve.accept(null);
                        }
                    } else {
                        // Packet could not be processed yet: pause queue without rejecting the caller.
                        // The same head packet remains queued and will be retried on restart().
                        stop();
                        resolve.accept(null);
                    }
                    return null;
                })
                .catchException(ex -> {
                    inFlightSince = 0;
                    stop();
                    IllegalStateException err = new IllegalStateException(failureMessage, ex);
                    reject.accept(err);
                    if (enqueued.reject != null) {
                        enqueued.reject.accept(err);
                    }
                });
        });
    }

    private void popHead() {
        try {
            queue.remove();
        } catch (NoSuchElementException ignored) {}
    }

    public void restartIfStuck(long timeoutMs) {
        if (closed) {
            return;
        }
        if (queue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (executionQueue == null) {
            if (now - lastRestartAttempt > timeoutMs) {
                if (handler.isReady()) {
                    logger.warning("Detected likely stuck queue... recovering");
                    restart();
                }
            }
            return;
        }
        if (inFlightSince > 0 && now - inFlightSince > timeoutMs) {
            logger.warning("Detected likely stuck packet... recovering");
            stop();
            restart();
        }
    }

    /**
     * External queue loop hook.
     * Useful when a caller wants to drive stuck detection/restart checks explicitly.
     */
    public void loop() {
        if (closed || queue.isEmpty()) {
            return;
        }
        restartIfStuck(stuckTimeoutMs);
        if (executionQueue == null && handler.isReady()) {
            restart();
        }
    }

    public void restart() {
        if (closed) {
            return;
        }
        if (executionQueue != null) {
            return;
        }
        lastRestartAttempt = System.currentTimeMillis();
        synchronized (this) {
            if (executionQueue != null) {
                return;
            }
            executionQueue = NGEPlatform.get().newExecutionQueue();
            for (Enqueued<T> enqueued : queue) {
                schedule(enqueued);
            }
        }
    }

    public void stop() {
        if (closed) {
            return;
        }
        this.epoch++;
        this.inFlightSince = 0;
        synchronized (this) {
            if (executionQueue == null) {
                return;
            }
            try {
                executionQueue.close();
            } catch (IOException e) {
                logger.log(Level.FINE, "Failed to close queue", e);
            }
            executionQueue = null;
        }
    }

    public void clear() {
        queue.clear();
    }

    private void startWatchdog() {
        watchdogExecutor.runLater(
            () -> {
                if (closed) {
                    return null;
                }
                restartIfStuck(stuckTimeoutMs);
                startWatchdog();
                return null;
            },
            watchdogIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void close() {
        closed = true;
        clear();
        this.epoch++;
        synchronized (this) {
            if (executionQueue != null) {
                try {
                    executionQueue.close();
                } catch (IOException e) {
                    logger.log(Level.FINE, "Failed to close queue", e);
                }
                executionQueue = null;
            }
        }
        try {
            watchdogExecutor.close();
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to close queue watchdog", e);
        }
    }
}
