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

    private void watchdog(NostrRelay relay) {
        if (relay.getStatus() != NostrRelay.Status.CONNECTED) {
            return;
        }
        NostrPool pool = new NostrPool();
        pool.connectRelay(relay);
        pool
            .fetch(new NostrFilter().limit(1).withKind(0), 1, Duration.ofMinutes(5))
            .then(evs -> {
                pool.close();
                return null;
            })
            .catchException(ex -> {
                pool.close();
                relay.disconnect("killed by watchdog due to unresponsiveness", true);
            });
    }

    @Override
    public boolean onRelayConnectRequest(NostrRelay relay) {
        return true;
    }

    @Override
    public boolean onRelayConnect(NostrRelay relay) {
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
    public boolean onRelayLoop(NostrRelay relay, Instant nowInstant) {
        if (Duration.between(this.lastCheck, nowInstant).compareTo(this.checkInterval) >= 0) {
            this.lastCheck = nowInstant;
            this.watchdog(relay);
        }
        return true;
    }

    @Override
    public boolean onRelayDisconnect(NostrRelay relay, String reason, boolean byClient) {
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
