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
package org.ngengine.nostr4j.utils;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

// thread safe
public final class ExponentialBackoff {

    private final Duration initialDelay;
    private final Duration maxDelay;
    private final Duration cooldown;
    private final float multiplier;

    private Duration currentDelay;
    private Instant nextAttemptAt = null;
    private Instant cooldownStartAt = null;

    public ExponentialBackoff() {
        this(Duration.ofSeconds(1), Duration.ofSeconds(120), Duration.ofSeconds(21), 2.0f);
    }

    public ExponentialBackoff(Duration initialDelay, Duration maxDelay, Duration cooldown, float multiplier) {
        this.initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
        this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");

        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("Initial delay must be > 0");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("Max delay must be >= initial delay");
        }
        if (multiplier <= 1.0f) {
            throw new IllegalArgumentException("Multiplier must be > 1.0");
        }
        if (cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("Cooldown must be > 0");
        }

        this.multiplier = multiplier;
        this.currentDelay = initialDelay;
    }

    /**
     * Register a failure. Increases the delay for the next attempt and schedules
     * nextAttemptAt.
     * @deprecated use registerAttempt()
     */
    @Deprecated
    public synchronized void registerFailure() {
        registerFailure(Instant.now());
    }

    /**
     * Register an attempt. Increases the delay for the next attempt and schedules
     * nextAttemptAt.
     * 
     */
    public synchronized void registerAttempt() {
        registerAttempt(Instant.now());
    }

    /**
     * @deprecated use registerAttempt()
     */
    @Deprecated
    public synchronized void registerFailure(Instant now) {
        cooldownStartAt = null;
        nextAttemptAt = now.plus(currentDelay);
        currentDelay = minDuration(multiply(currentDelay, multiplier), maxDelay);
    }

    public synchronized void registerAttempt(Instant now) {
        cooldownStartAt = null;
        nextAttemptAt = now.plus(currentDelay);
        currentDelay = minDuration(multiply(currentDelay, multiplier), maxDelay);
    }

    /**
     * Register a success. Starts the cooldown window; after cooldown elapses, delay
     * resets.
     */
    public synchronized void registerSuccess() {
        registerSuccess(Instant.now());
    }

    public synchronized void registerSuccess(Instant now) {
        cooldownStartAt = now;
    }

    /**
     * Returns how long to wait before the next attempt (zero if you can try now).
     */
    public Duration getDelay() {
        return getDelay(Instant.now());
    }

    /**
     * Returns how long to wait before the next attempt (zero if you can try now)
     * using the provided time.
     */
    public synchronized Duration getDelay(Instant now) {
        // Cooldown-based reset after a success
        if (cooldownStartAt != null) {
            Duration sinceSuccess = Duration.between(cooldownStartAt, now);
            if (sinceSuccess.compareTo(cooldown) > 0) {
                currentDelay = initialDelay;
                cooldownStartAt = null;
                nextAttemptAt = null;
            }
        }

        if (nextAttemptAt == null) {
            return Duration.ZERO;
        }

        return nextAttemptAt.isAfter(now) ? Duration.between(now, nextAttemptAt) : Duration.ZERO;
    }

    private static Duration multiply(Duration d, float m) {
        // Use nanos for precision; round to nearest nanosecond
        long nanos = d.toNanos();
        long scaled = Math.round(nanos * (double) m);
        if (scaled < 0) scaled = Long.MAX_VALUE; // guard overflow
        return Duration.ofNanos(scaled);
    }

    private static Duration minDuration(Duration a, Duration b) {
        return (a.compareTo(b) <= 0) ? a : b;
    }
}
