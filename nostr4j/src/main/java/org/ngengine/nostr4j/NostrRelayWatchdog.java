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

package org.ngengine.nostr4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.proto.NostrMessage;

/**
 * Last resort watchdog that attempts to reconnect to the relay
 * if it becomes unresponsive or broken even if the connection is still alive.
 * (eg. some invalid state or breakage on the relay side)
 */
public class NostrRelayWatchdog implements NostrRelayComponent {

    private Instant lastCheck = Instant.EPOCH;
    private Duration checkInterval = Duration.ofMinutes(10);
    private final Map<NostrRelay, AtomicLong> scheduleGenerations = new ConcurrentHashMap<>();

    protected void runWatchdog(NostrRelay relay) {
        if (relay.getStatus() != NostrRelay.Status.CONNECTED) {
            return;
        }
        NostrPool pool = new NostrPool();
        pool.addRelay(relay);
        pool
            .fetch(new NostrFilter().limit(1).withKind(0), 1, Duration.ofMinutes(5))
            .then(evs -> {
                pool.clean();
                relay.disconnect("watchdog", false);
                return null;
            })
            .catchException(ex -> {
                pool.clean();
                relay.disconnect("killed by watchdog due to unresponsiveness", true);
            });
    }

    protected void invalidateSchedule(NostrRelay relay) {
        scheduleGenerations.computeIfAbsent(relay, r -> new AtomicLong()).incrementAndGet();
    }

    protected void scheduleWatchdog(NostrRelay relay) {
        long generation = scheduleGenerations.computeIfAbsent(relay, r -> new AtomicLong()).incrementAndGet();
        long delayMs = Math.max(1L, this.checkInterval.toMillis());
        relay.executor.runLater(
            () -> {
                AtomicLong current = scheduleGenerations.get(relay);
                if (current == null || current.get() != generation) {
                    return null;
                }
                if (relay.getStatus() != NostrRelay.Status.CONNECTED) {
                    return null;
                }
                this.lastCheck = Instant.now();
                runWatchdog(relay);
                scheduleWatchdog(relay);
                return null;
            },
            delayMs,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public boolean onRelayConnectRequest(NostrRelay relay) {
        return true;
    }

    @Override
    public boolean onRelayConnect(NostrRelay relay) {
        scheduleWatchdog(relay);
        return true;
    }

    @Override
    public boolean onRelayMessage(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelayError(NostrRelay relay, Throwable error) {
        return true;
    }

    @Override
    public boolean onRelayDisconnect(NostrRelay relay, String reason, boolean byClient) {
        invalidateSchedule(relay);
        return true;
    }

    @Override
    public boolean onRelayBeforeSend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelaySend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelayAfterSend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelayDisconnectRequest(NostrRelay relay, String reason) {
        return true;
    }
}
