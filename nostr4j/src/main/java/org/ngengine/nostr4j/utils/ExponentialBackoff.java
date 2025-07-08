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

import java.util.concurrent.TimeUnit;

// non thread safe
public final class ExponentialBackoff {

    private final long initialDelay;
    private final long maxDelay;
    private final float multiplier;
    private final long cooldown;
    private final TimeUnit timeUnit;

    private long currentDelay;
    private long nextAttemptTimestamp = 0;
    private long cooldownStartTimestamp = 0;

    public ExponentialBackoff() {
        this(1, 2 * 60, 21, TimeUnit.SECONDS, 2.0f);
    }

    public ExponentialBackoff(long initialDelay, long maxDelay, long cooldown, TimeUnit timeUnit, float multiplier) {
        if (initialDelay <= 0) {
            throw new IllegalArgumentException("Initial delay must be positive");
        }
        if (maxDelay < initialDelay) {
            throw new IllegalArgumentException("Max delay must be >= initial delay");
        }
        if (multiplier <= 1.0f) {
            throw new IllegalArgumentException("Multiplier must be > 1.0");
        }
        if (cooldown <= 0) {
            throw new IllegalArgumentException("Cooldown period must be positive");
        }
        this.timeUnit = timeUnit;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.cooldown = cooldown;
        this.currentDelay = initialDelay;
    }

    /**
     * Register a failure. This will increase the delay for the next attempt.
     */
    public void registerFailure() {
        cooldownStartTimestamp = 0;
        nextAttemptTimestamp = timeUnit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS) + currentDelay;
        currentDelay = Math.min((long) (currentDelay * multiplier), maxDelay);
    }

    /**
     * Register a success. This will reset the delay to the initial value.
     */
    public void registerSuccess() {
        cooldownStartTimestamp = timeUnit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Get the delay for the next attempt.
     * @param now current time
     * @param unit the time unit for the now parameter and the return value
     * @return the delay for the next attempt in the specified time unit
     */
    public long getNextAttemptTime(long now, TimeUnit unit) {
        long nowInternal = timeUnit.convert(now, unit);
        if (cooldownStartTimestamp != 0 && (nowInternal - cooldownStartTimestamp) > cooldown) {
            currentDelay = initialDelay;
            cooldownStartTimestamp = 0;
            nextAttemptTimestamp = 0;
        }

        long remaining = Math.max(0, nextAttemptTimestamp - nowInternal);
        return unit.convert(remaining, timeUnit);
    }
}
