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

package org.ngengine.nostr4j.pool.fetchpolicy;

import static org.ngengine.platform.NGEUtils.dbg;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.listeners.sub.NostrSubAllListener;

public class NostrWaitForEventFetchPolicy implements NostrPoolFetchPolicy {

    private static final Logger logger = Logger.getLogger(NostrPool.class.getName());
    private final Predicate<SignedNostrEvent> filter;
    private AtomicInteger count = new AtomicInteger(0);
    private final int numEventsToWait;
    private final boolean endOnEose;

    public static NostrWaitForEventFetchPolicy get(Predicate<SignedNostrEvent> filter, int numEventsToWait, boolean endOnEose) {
        return new NostrWaitForEventFetchPolicy(filter, numEventsToWait, endOnEose);
    }

    public NostrWaitForEventFetchPolicy(Predicate<SignedNostrEvent> filter, int numEventsToWait, boolean endOnEose) {
        this.filter = filter;
        this.numEventsToWait = numEventsToWait;
        this.endOnEose = endOnEose;
    }

    @Override
    public NostrSubAllListener getListener(NostrSubscription sub, List<SignedNostrEvent> events, Runnable end) {
        return new NostrSubAllListener() {
            @Override
            public void onSubEvent(SignedNostrEvent e, boolean stored) {
                assert dbg(() -> {
                    logger.finer("fetch event " + e + " for subscription " + sub.getId());
                });
                if (filter.test(e)) {
                    events.add(e);
                    if (count.incrementAndGet() >= numEventsToWait) {
                        end.run();
                    }
                }
            }

            @Override
            public void onSubClose(List<String> reason) {
                assert dbg(() -> {
                    logger.fine("fetch close " + reason + " for subscription " + sub.getId());
                });
                end.run();
            }

            @Override
            public void onSubEose(NostrRelay relay, boolean all) {
                if (endOnEose && all) {
                    end.run();
                    assert dbg(() -> {
                        logger.fine("fetch eose for fetch " + sub.getId() + " with received events: " + events);
                    });
                }
            }
        };
    }
}
