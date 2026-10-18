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
package org.ngengine.nostr4j.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncTask;

/**
 * Stores events in memory.
 */
public class NostrInMemoryEventStore implements NostrEventStore {
    private static final class StoredEntry {
        final Supplier<SignedNostrEvent> event;
        final Instant received;

        StoredEntry(Supplier<SignedNostrEvent> event, Instant received) {
            this.event = event;
            this.received = received;
        }

        Entry read() {
            SignedNostrEvent value = event.get();
            return value == null ? null : new Entry(value, received);
        }
    }

    private final Map<String, StoredEntry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, byte[]> metadata = new LinkedHashMap<>();
    private final NostrEventIndex index = new NostrEventIndex();
    private int capacity;

    public NostrInMemoryEventStore() {
        this(Integer.MAX_VALUE);
    }

    public NostrInMemoryEventStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Store capacity must be positive");
        this.capacity = capacity;
    }

    protected void cleanup() {}

    protected final void removeCollected(String id) {
        synchronized (entries) {
            StoredEntry entry = entries.get(id);
            if (entry != null && entry.event.get() == null) { entries.remove(id); index.remove(id); }
        }
    }

    protected Supplier<SignedNostrEvent> retain(SignedNostrEvent event) {
        return () -> event;
    }

    public AsyncTask<Void> setCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Store capacity must be positive");
        synchronized (entries) {
            cleanup();
            this.capacity = capacity;
            trim();
        }
        return AsyncTask.completed(null);
    }

    private void trim() {
        while (entries.size() > capacity) {
            String id = entries.keySet().iterator().next();
            entries.remove(id);
            index.remove(id);
        }
    }

    @Override
    public AsyncTask<Entry> readEntry(String id) {
        synchronized (entries) {
            cleanup();
            StoredEntry stored = entries.get(id);
            Entry entry = stored == null ? null : stored.read();
            if (stored != null && entry == null) { entries.remove(id); index.remove(id); }
            return AsyncTask.completed(entry);
        }
    }

    @Override
    public AsyncTask<Void> writeEntry(String id, Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!entry.event.getId().equals(id)) return AsyncTask.failed(new IllegalArgumentException("Store key does not match event ID"));
        synchronized (entries) {
            cleanup();
            StoredEntry previous = entries.get(id);
            if (previous == null || previous.read() == null) {
                entries.put(id, new StoredEntry(retain(entry.event), entry.received));
                index.put(new NostrEventIndex.Metadata(entry));
                trim();
            }
        }
        return AsyncTask.completed(null);
    }

    @Override
    public AsyncTask<Entry> removeEntry(String id) {
        synchronized (entries) {
            StoredEntry removed = entries.remove(id);
            index.remove(id);
            return AsyncTask.completed(removed == null ? null : removed.read());
        }
    }

    @Override
    public AsyncTask<List<String>> entryIds() {
        synchronized (entries) {
            cleanup();
            Iterator<StoredEntry> iterator = entries.values().iterator();
            while (iterator.hasNext()) if (iterator.next().read() == null) iterator.remove();
            for (String id : index.ids()) if (!entries.containsKey(id)) index.remove(id);
            return AsyncTask.completed(new ArrayList<>(entries.keySet()));
        }
    }

    @Override
    public AsyncTask<Void> clear() {
        synchronized (entries) {
            cleanup();
            entries.clear();
            index.clear();
        }
        return AsyncTask.completed(null);
    }

    @Override
    public AsyncTask<Iterator<NostrEventIndex.Metadata>> candidates(NostrFilter filter,
            Predicate<NostrEventIndex.Metadata> eligible) {
        synchronized (entries) {
            cleanup();
            return AsyncTask.completed(index.candidates(filter, eligible));
        }
    }

    @Override
    public AsyncTask<List<NostrEventIndex.Metadata>> maintenanceBatch(String after, int count) {
        synchronized (entries) {
            cleanup();
            return AsyncTask.completed(index.batch(after, count));
        }
    }

    @Override
    public AsyncTask<byte[]> readMetadata(String name) {
        synchronized (metadata) {
            byte[] value = metadata.get(name);
            return AsyncTask.completed(value == null ? null : value.clone());
        }
    }

    @Override
    public AsyncTask<Void> writeMetadata(String name, byte[] value) {
        synchronized (metadata) { metadata.put(name, value.clone()); }
        return AsyncTask.completed(null);
    }
}
