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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.nip50.NostrSearchFilter;
import org.ngengine.platform.NGEUtils;

/**
 * Finds events by author, kind, date and tags.
 */
final class NostrEventIndex {
    /**
     * An event's filter fields, receipt time and expiration.
     */
    static final class Metadata {
        final String id;
        final String author;
        final int kind;
        final Instant created;
        final Instant received;
        final Instant expiration;
        final List<List<String>> tags;

        Metadata(NostrEventStore.Entry entry) {
            SignedNostrEvent event = entry.event;
            id = event.getId();
            author = event.getPubkey().asHex();
            kind = event.getKind();
            created = event.getCreatedAt();
            received = entry.received;
            expiration = event.getExpiration();
            tags = copyTags(event.getTagRows());
        }

        private Metadata(Map<String, Object> raw) {
            id = (String) raw.get("id");
            author = (String) raw.get("author");
            if (id == null || !id.matches("[a-f0-9]{64}") || author == null || !author.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid event index ID or author");
            if (!(raw.get("kind") instanceof Number)) throw new IllegalArgumentException("Invalid indexed kind");
            kind = NGEUtils.safeInt(raw.get("kind"));
            created = Instant.parse((String) raw.get("created"));
            received = Instant.parse((String) raw.get("received"));
            expiration = Instant.parse((String) raw.get("expiration"));
            tags = copyTags((List<List<String>>) raw.get("tags"));
        }

        private static List<List<String>> copyTags(List<List<String>> rows) {
            List<List<String>> copy = new ArrayList<>();
            for (List<String> row : rows) {
                List<String> values = new ArrayList<>();
                for (Object value : row) {
                    if (!(value instanceof String)) throw new IllegalArgumentException("Invalid indexed tag");
                    values.add((String) value);
                }
                copy.add(Collections.unmodifiableList(values));
            }
            return Collections.unmodifiableList(copy);
        }

        boolean isEphemeral() { return kind >= 20000 && kind < 30000; }
        boolean isReplaceable() { return kind == 0 || kind == 3 || kind >= 10000 && kind < 20000; }
        boolean isAddressable() { return kind >= 30000 && kind < 40000; }

        String address() {
            String identifier = "";
            if (isAddressable()) for (List<String> row : tags) {
                if (row.size() > 1 && "d".equals(row.get(0))) { identifier = row.get(1); break; }
            }
            return kind + ":" + author + ":" + identifier;
        }

        boolean matches(NostrFilter filter) {
            if (filter.getClass() != NostrFilter.class) return true;
            if (filter.getIds() != null && !filter.getIds().contains(id)) return false;
            if (filter.getAuthors() != null && !filter.getAuthors().contains(author)) return false;
            if (filter.getKinds() != null && !filter.getKinds().contains(kind)) return false;
            if (filter.getSince() != null && created.isBefore(filter.getSince())) return false;
            if (filter.getUntil() != null && created.isAfter(filter.getUntil())) return false;
            if (filter.getTags() != null) for (Map.Entry<String, List<String>> rule : filter.getTags().entrySet()) {
                boolean matched = false;
                for (List<String> row : tags) if (row.size() > 1 && row.get(0).equals(rule.getKey()) && rule.getValue().contains(row.get(1))) {
                    matched = true;
                    break;
                }
                if (!matched) return false;
            }
            return true;
        }

        Map<String, Object> toMap() {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("id", id);
            raw.put("author", author);
            raw.put("kind", kind);
            raw.put("created", created.toString());
            raw.put("received", received.toString());
            raw.put("expiration", expiration.toString());
            raw.put("tags", tags);
            return raw;
        }

        static Metadata fromMap(Map<String, Object> raw) { return new Metadata(raw); }
    }

    private static final Comparator<Metadata> ORDER = Comparator.comparing((Metadata entry) -> entry.created)
        .reversed().thenComparing(entry -> entry.id);
    private final NavigableMap<String, Metadata> entries = new TreeMap<>();
    private final Map<String, Map<String, Metadata>> buckets = new HashMap<>();
    private final NavigableSet<Metadata> ordered = new TreeSet<>(ORDER);
    private final Map<String, NavigableSet<Metadata>> authors = new HashMap<>();
    private final Map<Integer, NavigableSet<Metadata>> kinds = new HashMap<>();
    private final Map<String, Map<String, NavigableSet<Metadata>>> tags = new HashMap<>();
    private final Map<String, NavigableSet<Metadata>> versions = new HashMap<>();

    synchronized void put(Metadata entry) {
        remove(entry.id);
        entries.put(entry.id, entry);
        buckets.computeIfAbsent(entry.id.substring(0, 2), ignored -> new HashMap<>()).put(entry.id, entry);
        ordered.add(entry);
        add(authors, entry.author, entry);
        add(kinds, entry.kind, entry);
        for (List<String> row : entry.tags) if (row.size() > 1)
            add(tags.computeIfAbsent(row.get(0), ignored -> new HashMap<>()), row.get(1), entry);
        if (entry.isReplaceable() || entry.isAddressable()) add(versions, entry.address(), entry);
    }

    synchronized void remove(String id) {
        Metadata entry = entries.remove(id);
        if (entry == null) return;
        ordered.remove(entry);
        Map<String, Metadata> bucket = buckets.get(id.substring(0, 2));
        bucket.remove(id);
        if (bucket.isEmpty()) buckets.remove(id.substring(0, 2));
        remove(authors, entry.author, entry);
        remove(kinds, entry.kind, entry);
        for (List<String> row : entry.tags) if (row.size() > 1) {
            Map<String, NavigableSet<Metadata>> values = tags.get(row.get(0));
            if (values != null) {
                remove(values, row.get(1), entry);
                if (values.isEmpty()) tags.remove(row.get(0));
            }
        }
        if (entry.isReplaceable() || entry.isAddressable()) remove(versions, entry.address(), entry);
    }

    private static <T> void add(Map<T, NavigableSet<Metadata>> index, T key, Metadata entry) {
        index.computeIfAbsent(key, ignored -> new TreeSet<>(ORDER)).add(entry);
    }

    private static <T> void remove(Map<T, NavigableSet<Metadata>> index, T key, Metadata entry) {
        NavigableSet<Metadata> values = index.get(key);
        if (values == null) return;
        values.remove(entry);
        if (values.isEmpty()) index.remove(key);
    }

    private static <T> NavigableSet<Metadata> union(Map<T, NavigableSet<Metadata>> index, Collection<T> keys) {
        if (keys.size() == 1) {
            NavigableSet<Metadata> values = index.get(keys.iterator().next());
            return values == null ? new TreeSet<>(ORDER) : values;
        }
        NavigableSet<Metadata> result = new TreeSet<>(ORDER);
        for (T key : keys) {
            NavigableSet<Metadata> values = index.get(key);
            if (values != null) result.addAll(values);
        }
        return result;
    }

    /**
     * Visits matching metadata from newest to oldest.
     */
    synchronized Iterator<Metadata> candidates(NostrFilter filter, Predicate<Metadata> eligible) {
        if (filter instanceof NostrSearchFilter || filter.getLimit() != null && filter.getLimit() <= 0)
            return Collections.emptyIterator();
        NavigableSet<Metadata> source = ordered;
        if (filter.getClass() == NostrFilter.class) {
            if (filter.getAuthors() != null) source = smaller(source, union(authors, filter.getAuthors()));
            if (filter.getKinds() != null) source = smaller(source, union(kinds, filter.getKinds()));
            if (filter.getTags() != null) for (Map.Entry<String, List<String>> rule : filter.getTags().entrySet())
                source = smaller(source, union(tags.getOrDefault(rule.getKey(), Collections.emptyMap()), rule.getValue()));
        }
        NavigableSet<Metadata> selected = source;
        return new Iterator<Metadata>() {
            private Metadata previous;
            private Metadata next;
            private boolean finished;

            @Override
            public boolean hasNext() {
                synchronized (NostrEventIndex.this) {
                    while (next == null && !finished) {
                        Metadata entry = previous == null ? (selected.isEmpty() ? null : selected.first()) : selected.higher(previous);
                        if (entry == null || filter.getClass() == NostrFilter.class && filter.getSince() != null
                                && entry.created.isBefore(filter.getSince())) { finished = true; break; }
                        previous = entry;
                        if (!entry.matches(filter) || !eligible.test(entry)) continue;
                        if (filter.getIds() == null && (entry.isReplaceable() || entry.isAddressable())) {
                            NavigableSet<Metadata> values = versions.get(entry.address());
                            Metadata latest = null;
                            if (values != null) for (Metadata version : values) if (eligible.test(version)) { latest = version; break; }
                            if (latest == null || !entry.id.equals(latest.id)) continue;
                        }
                        next = entry;
                    }
                    return next != null;
                }
            }

            @Override
            public Metadata next() {
                synchronized (NostrEventIndex.this) {
                    if (!hasNext()) throw new java.util.NoSuchElementException();
                    Metadata result = next;
                    next = null;
                    return result;
                }
            }
        };
    }

    private static NavigableSet<Metadata> smaller(NavigableSet<Metadata> first, NavigableSet<Metadata> second) {
        return second.size() < first.size() ? second : first;
    }

    synchronized List<Metadata> all() { return new ArrayList<>(ordered); }
    synchronized Set<String> ids() { return new LinkedHashSet<>(entries.keySet()); }
    synchronized Metadata get(String id) { return entries.get(id); }
    synchronized List<Metadata> bucket(String prefix) {
        return new ArrayList<>(buckets.getOrDefault(prefix, Collections.emptyMap()).values());
    }

    synchronized List<Metadata> batch(String after, int count) {
        List<Metadata> result = new ArrayList<>();
        if (count < 1) throw new IllegalArgumentException("Batch size must be positive");
        Collection<Metadata> tail = after == null ? entries.values() : entries.tailMap(after, false).values();
        for (Metadata entry : tail) { result.add(entry); if (result.size() == count) return result; }
        if (after != null) for (Metadata entry : entries.headMap(after, true).values()) {
            result.add(entry); if (result.size() == count) break;
        }
        return result;
    }

    synchronized void clear() {
        entries.clear(); buckets.clear(); ordered.clear(); authors.clear(); kinds.clear(); tags.clear(); versions.clear();
    }
}
