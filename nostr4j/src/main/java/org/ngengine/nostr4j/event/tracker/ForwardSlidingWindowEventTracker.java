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
package org.ngengine.nostr4j.event.tracker;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public class ForwardSlidingWindowEventTracker implements EventTracker {

    protected final LinkedList<SignedNostrEvent.Identifier> seenEvents = new LinkedList<SignedNostrEvent.Identifier>();

    protected int maxTrackedEvents;
    protected final int minTrackedEvents;
    protected final long trackingWindowS;
    protected final long trackingWindowsMarginS;
    protected long cutOffS = 0;

    public ForwardSlidingWindowEventTracker() {
        this(Integer.MAX_VALUE, 21, 60, TimeUnit.MINUTES, 30, TimeUnit.MINUTES);
    }

    public ForwardSlidingWindowEventTracker(
        int maxTrackedEvents,
        int minTrackedEvents,
        long trackingWindow,
        TimeUnit trackingWindowTimeUnit,
        long trackingWindowMargin,
        TimeUnit trackingWindowMarginTimeUnit
    ) {
        this.maxTrackedEvents = maxTrackedEvents;
        this.minTrackedEvents = minTrackedEvents < 0 ? 0 : minTrackedEvents;
        this.trackingWindowS = trackingWindowTimeUnit.toSeconds(trackingWindow);
        this.trackingWindowsMarginS = trackingWindowMarginTimeUnit.toSeconds(trackingWindowMargin);
    }

    @Override
    public boolean seen(SignedNostrEvent event) {
        synchronized (seenEvents) {
            if (event.getCreatedAt().getEpochSecond() < cutOffS) {
                return true;
            }
            SignedNostrEvent.Identifier newEventId = event.getIdentifier();
            ListIterator<SignedNostrEvent.Identifier> it = seenEvents.listIterator();

            // Check if the event is already seen
            // if it is not, add it to the list (ordered from most recent to oldest)
            while (it.hasNext()) {
                SignedNostrEvent.Identifier seenEventId = it.next();
                if (seenEventId.id.equals(newEventId.id)) {
                    return true;
                }
                if (seenEventId.createdAt < newEventId.createdAt) {
                    it.previous();
                    it.add(newEventId);
                    update();
                    assert checkOrder() : "Events are not in order";
                    return false;
                }
            }
            // if we reach here, the event is older than all seen events
            // add it to the end of the list
            it.add(newEventId);
            update();
            assert checkOrder() : "Events are not in order (2)";
            return false;
        }
    }

    protected int count() {
        return seenEvents.size();
    }

    protected long currentTimeSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    protected Collection<SignedNostrEvent.Identifier> getAll() {
        return Collections.unmodifiableCollection(seenEvents);
    }

    protected void update() {
        if (seenEvents.size() <= minTrackedEvents) {
            return;
        }
        synchronized (seenEvents) {
            int toRemove = seenEvents.size() - maxTrackedEvents;
            if (toRemove < 0) toRemove = 0;

            boolean cutOffUpdate = false;

            long t = currentTimeSeconds();
            if (t - cutOffS > trackingWindowS) {
                cutOffUpdate = true;
                cutOffS = t - (trackingWindowS - trackingWindowsMarginS);
                assert cutOffS <= currentTimeSeconds() : "Cut off time is in the future " +
                cutOffS +
                " > " +
                currentTimeSeconds();
            }

            assert checkOrder() : "Events are not in order";
            assert toRemove >= 0 &&
            toRemove <= seenEvents.size() &&
            toRemove <= maxTrackedEvents : "Invalid number of events to remove";

            ListIterator<SignedNostrEvent.Identifier> it = seenEvents.listIterator(seenEvents.size());

            // remove the oldest events
            int removed = 0;
            while (it.hasPrevious()) {
                SignedNostrEvent.Identifier seenEventId = it.previous();
                if (cutOffUpdate && seenEventId.createdAt < cutOffS) {
                    it.remove();
                    removed++;
                    continue;
                }
                if (removed < toRemove) {
                    it.remove();
                    removed++;
                    continue;
                }
                break;
            }

            // if we removed some events, we need to update the cut off time
            // to the last event in the list, unless the cut off time is already set
            // to a newer timestamp
            if (toRemove > 0 && seenEvents.size() > 0) {
                cutOffS = Math.max(cutOffS, seenEvents.getLast().createdAt);
            }
            assert cutOffS <= currentTimeSeconds() || cutOffS >= seenEvents.getLast().createdAt : "Cut off time is invalid";

            assert seenEvents.size() <= maxTrackedEvents : "Too many events";
            assert checkOrder() : "Events are not in order";
        }
    }

    protected boolean checkOrder() {
        long last = Long.MAX_VALUE;
        if (seenEvents.isEmpty()) {
            return true;
        }

        for (SignedNostrEvent.Identifier id : seenEvents) {
            if (id.createdAt > last) {
                return false;
            }
            last = id.createdAt;
        }
        return true;
    }

    @Override
    public void tuneFor(NostrSubscription sub) {
        int maxLimit = -1;
        for (NostrFilter filter : sub.getFilters()) {
            Number limit = filter.getLimit();
            if (limit != null && limit.intValue() > maxLimit) {
                maxLimit = limit.intValue();
            }
        }
        if (maxLimit > 0) {
            maxTrackedEvents = maxLimit * 2;
        }

        long earlistSinceS = Long.MAX_VALUE;
        for (NostrFilter filter : sub.getFilters()) {
            if (filter.getSince() != null) {
                long since = filter.getSince().getEpochSecond();
                if (since < earlistSinceS) {
                    earlistSinceS = since;
                }
            }
        }

        if (earlistSinceS != Long.MAX_VALUE) {
            cutOffS = Math.max(cutOffS, earlistSinceS);
        }
    }
}
