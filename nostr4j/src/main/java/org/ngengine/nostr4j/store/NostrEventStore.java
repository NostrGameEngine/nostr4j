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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.nip50.NostrSearchFilter;
import org.ngengine.platform.AsyncTask;

/**
 * Stores Nostr events.
 */
public interface NostrEventStore {
    /**
     * An event and the time it was first received.
     */
    final class Entry {
        public final SignedNostrEvent event;
        public final Instant received;

        public Entry(SignedNostrEvent event, Instant received) {
            this.event = Objects.requireNonNull(event, "event");
            this.received = Objects.requireNonNull(received, "received");
        }
    }

    AsyncTask<Entry> readEntry(String id);

    /**
     * Saves a new event. Duplicate IDs keep their original receipt time.
     */
    AsyncTask<Void> writeEntry(String id, Entry entry);

    AsyncTask<Entry> removeEntry(String id);
    /**
     * Removes an event when its contents are not needed.
     */
    default AsyncTask<Void> discardEntry(String id) { return removeEntry(id).then(ignored -> null); }

    AsyncTask<List<String>> entryIds();

    /**
     * Removes events without deleting metadata or unrelated files.
     */
    AsyncTask<Void> clear();

    AsyncTask<byte[]> readMetadata(String name);
    AsyncTask<Void> writeMetadata(String name, byte[] value);

    default AsyncTask<Void> addEvent(SignedNostrEvent event) {
        return writeEntry(event.getId(), new Entry(event, Instant.now()));
    }

    default AsyncTask<SignedNostrEvent> get(String id) {
        return readEntry(id).then(entry -> entry == null ? null : entry.event);
    }

    default AsyncTask<Integer> size() {
        return entryIds().then(List::size);
    }

    default AsyncTask<List<SignedNostrEvent>> getEvents(List<NostrFilter> filters) {
        return getEvents(filters, null);
    }

    default AsyncTask<List<SignedNostrEvent>> getEvents(List<NostrFilter> filters, List<SignedNostrEvent> results) {
        return query(filters).then(events -> {
            if (results == null) return events;
            results.addAll(events);
            return results;
        });
    }

    default AsyncTask<List<SignedNostrEvent>> query(Collection<NostrFilter> filters) {
        return query(this, filters, this::get, entry -> true);
    }

    /**
     * Returns filter candidates from newest to oldest.
     */
    default AsyncTask<Iterator<NostrEventIndex.Metadata>> candidates(NostrFilter filter,
            Predicate<NostrEventIndex.Metadata> eligible) {
        NostrEventIndex index = new NostrEventIndex();
        return entryIds().compose(ids -> visit(ids, id -> readEntry(id).then(entry -> {
            if (entry != null) index.put(new NostrEventIndex.Metadata(entry));
            return null;
        }))).then(ignored -> index.candidates(filter, eligible));
    }

    /**
     * Returns at most count metadata entries after the previous ID, wrapping at the end.
     */
    default AsyncTask<List<NostrEventIndex.Metadata>> maintenanceBatch(String after, int count) {
        NostrEventIndex index = new NostrEventIndex();
        return entryIds().compose(ids -> visit(ids, id -> readEntry(id).then(entry -> {
            if (entry != null) index.put(new NostrEventIndex.Metadata(entry));
            return null;
        }))).then(ignored -> index.batch(after, count));
    }

    static String address(SignedNostrEvent event) {
        String identifier = event.isAddressable() ? event.getFirstTagFirstValue("d") : "";
        return event.getKind() + ":" + event.getPubkey().asHex() + ":" + (identifier == null ? "" : identifier);
    }

    Comparator<SignedNostrEvent> ORDER = Comparator
        .comparing(SignedNostrEvent::getCreatedAt).reversed().thenComparing(SignedNostrEvent::getId);

    /**
     * Reads only enough matching events to fill each filter's limit.
     */
    static AsyncTask<List<SignedNostrEvent>> query(NostrEventStore store, Collection<NostrFilter> filters,
            Function<String, AsyncTask<SignedNostrEvent>> lookup,
            Predicate<NostrEventIndex.Metadata> eligible) {
        List<NostrFilter> snapshot = new ArrayList<>();
        for (NostrFilter filter : filters) snapshot.add(filter.clone());
        Map<String, SignedNostrEvent> selected = new LinkedHashMap<>();
        return visit(snapshot, filter -> {
            if (filter instanceof NostrSearchFilter || filter.getLimit() != null && filter.getLimit() <= 0)
                return AsyncTask.completed(null);
            if (filter.getIds() != null) {
                Map<String, SignedNostrEvent> explicit = new LinkedHashMap<>();
                return visit(new ArrayList<>(new LinkedHashSet<>(filter.getIds())), id -> lookup.apply(id).then(event -> {
                    if (event != null) explicit.put(id, event);
                    return null;
                })).then(ignored -> {
                    List<SignedNostrEvent> events = new ArrayList<>(explicit.values());
                    events.sort(ORDER);
                    int count = 0;
                    for (SignedNostrEvent event : events) if (filter.matches(event, count)) {
                        selected.put(event.getId(), event);
                        count++;
                    }
                    return null;
                });
            }
            return store.candidates(filter, eligible).compose(entries -> collect(entries, filter, lookup, selected, 0));
        }).then(ignored -> {
            List<SignedNostrEvent> result = new ArrayList<>(selected.values());
            result.sort(ORDER);
            return Collections.unmodifiableList(result);
        });
    }

    static AsyncTask<Void> collect(Iterator<NostrEventIndex.Metadata> entries, NostrFilter filter,
            Function<String, AsyncTask<SignedNostrEvent>> lookup, Map<String, SignedNostrEvent> selected, int count) {
        if (filter.getLimit() != null && count >= filter.getLimit() || !entries.hasNext()) return AsyncTask.completed(null);
        String id = entries.next().id;
        AsyncTask<SignedNostrEvent> read = selected.containsKey(id) ? AsyncTask.completed(selected.get(id)) : lookup.apply(id);
        return read.compose(event -> {
            int next = count;
            if (event != null && filter.matches(event, count)) { selected.put(id, event); next++; }
            return collect(entries, filter, lookup, selected, next);
        });
    }

    static <T> AsyncTask<Void> visit(List<T> values, Function<T, AsyncTask<?>> action) {
        AsyncTask<Void> task = AsyncTask.completed(null);
        for (T value : values) task = task.compose(ignored -> action.apply(value).then(done -> null));
        return task;
    }
}
