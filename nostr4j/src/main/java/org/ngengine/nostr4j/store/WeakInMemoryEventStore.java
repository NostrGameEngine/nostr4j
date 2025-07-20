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

package org.ngengine.nostr4j.store;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public class WeakInMemoryEventStore implements EventStore {

    // TODO: consider replacing with non-blocking data structures
    private final PriorityQueue<WeakReference<SignedNostrEvent>> eventQueue = new PriorityQueue<>((we1, we2) -> {
        SignedNostrEvent e1 = we1.get();
        SignedNostrEvent e2 = we2.get();
        if (e1 == null || e2 == null) {
            return e1 == null ? 1 : -1; // Nulls are considered less than non-nulls
        }
        Instant createdAt1 = e1.getCreatedAt();
        Instant createdAt2 = e2.getCreatedAt();
        return createdAt2.compareTo(createdAt1);
    });

    @Override
    public void addEvent(SignedNostrEvent event) {
        synchronized (eventQueue) {
            eventQueue.offer(new WeakReference<SignedNostrEvent>(event));
        }
    }

    @Override
    public List<SignedNostrEvent> getEvents(List<NostrFilter> filters, List<SignedNostrEvent> results) {
        if (results == null) {
            results = new ArrayList<>();
        }
        for (NostrFilter filter : filters) {
            int count = 0;
            synchronized (eventQueue) {
                Iterator<WeakReference<SignedNostrEvent>> iterator = eventQueue.iterator();
                while (iterator.hasNext()) {
                    WeakReference<SignedNostrEvent> weakRef = iterator.next();
                    SignedNostrEvent event = weakRef.get();
                    if (event == null) {
                        iterator.remove(); // Clean up null references
                        continue;
                    }
                    if (filter.matches(event, count)) {
                        results.add(event);
                        count++;
                    }
                }
            }
        }
        return results;
    }
}
