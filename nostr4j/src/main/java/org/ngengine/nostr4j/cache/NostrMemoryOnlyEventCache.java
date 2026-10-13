/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2026, Riccardo Balbo
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
package org.ngengine.nostr4j.cache;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.ngengine.platform.AsyncTask;

/**
 * Bounded event cache that retains payloads only in memory.
 * Access-order eviction preserves recently read events. Regular events have no freshness
 * limit by default, replaceable/addressable events live for 100 milliseconds, and
 * ephemeral events are excluded unless explicitly enabled. No external store is opened.
 */
public final class NostrMemoryOnlyEventCache extends AbstractNostrEventCache {
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private int capacity;

    /**
     * Creates an in-memory cache using the system clock.
     *
     * @param capacity positive maximum number of payloads retained in memory
     */
    public NostrMemoryOnlyEventCache(int capacity) {
        this(capacity, Instant::now);
    }

    /**
     * Creates an in-memory cache with an explicit clock for freshness and expiry checks.
     * Deletion records are bounded to four times the payload capacity per history.
     *
     * @param capacity positive maximum number of retained payloads
     * @param clock source of the current time
     */
    public NostrMemoryOnlyEventCache(int capacity, Supplier<Instant> clock) {
        super(deletionLimit(capacity), clock);
        this.capacity = capacity;
    }

    private static int deletionLimit(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Cache capacity must be positive");
        return (int) Math.min(Integer.MAX_VALUE, capacity * 4L);
    }

    /**
     * Changes the maximum payload count and immediately evicts the least-recently used entries.
     * The bounded deletion histories are trimmed to match the new capacity.
     *
     * @param capacity positive maximum number of retained payloads
     * @return task completing after eviction and history trimming
     */
    public AsyncTask<Void> setCapacity(int capacity) {
        int deletionCapacity = deletionLimit(capacity);
        return submit(() -> {
            this.capacity = capacity;
            trim();
            trimDeletionHistory(deletionCapacity);
            return AsyncTask.completed(null);
        });
    }

    private void trim() {
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    @Override
    protected AsyncTask<Entry> readEntry(String id) {
        return AsyncTask.completed(entries.get(id));
    }

    @Override
    protected AsyncTask<Void> writeEntry(String id, Entry entry) {
        entries.put(id, entry);
        trim();
        return AsyncTask.completed(null);
    }

    @Override
    protected AsyncTask<Entry> removeEntry(String id) {
        return AsyncTask.completed(entries.remove(id));
    }

    @Override
    protected AsyncTask<List<String>> entryIds() {
        return AsyncTask.completed(new ArrayList<>(entries.keySet()));
    }

    @Override
    protected AsyncTask<Void> clearEntries() {
        entries.clear();
        return AsyncTask.completed(null);
    }
}
