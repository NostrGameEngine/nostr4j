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

import static org.ngengine.nostr4j.store.NostrEventStore.address;
import static org.ngengine.nostr4j.store.NostrEventStore.visit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.store.NostrEventIndex.Metadata;
import org.ngengine.nostr4j.store.NostrEventStore.Entry;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ExecutionQueue;
import org.ngengine.platform.NGEPlatform;

/**
 * Stores events and applies expiration and deletion rules.
 */
public final class NostrEventCache {
    private final NostrEventStore store;
    private static final Logger logger = Logger.getLogger(NostrEventCache.class.getName());
    private final Map<String, Instant> deletedIds = new LinkedHashMap<>();
    private final Map<String, Instant> deletedAddresses = new LinkedHashMap<>();
    private final Supplier<Instant> clock;
    private final List<Consumer<SignedNostrEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor(NostrEventCache.class);
    private final ExecutionQueue operations = NGEPlatform.get().newExecutionQueue(executor);
    private final Runnable closer = NGEPlatform.get().registerFinalizer(this, executor::close);
    private boolean initialized;
    private String cleanAfter;
    private final int deletionCapacity;
    private volatile Duration ttl;
    private volatile Duration replaceableTtl = Duration.ofMillis(100);
    private volatile Duration ephemeralTtl = Duration.ZERO;
    private volatile boolean honorDeletions;

    /**
     * Creates a cache using the supplied store. The caller keeps ownership of the store.
     */
    public NostrEventCache(NostrEventStore store) {
        this(store, 4096, Instant::now);
    }

    /**
     * Creates a cache with a clock and a limit on saved deletion records.
     */
    public NostrEventCache(NostrEventStore store, int deletionCapacity, Supplier<Instant> clock) {
        this.store = Objects.requireNonNull(store, "store");
        if (deletionCapacity < 1) throw new IllegalArgumentException("Deletion capacity must be positive");
        this.deletionCapacity = deletionCapacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the store used by this cache.
     */
    public NostrEventStore getStore() {
        return store;
    }

    private AsyncTask<Entry> readEntry(String id) {
        return store.readEntry(id).compose(entry -> {
            if (entry == null) return AsyncTask.completed(null);
            return entry.event.verifyAsync().compose(valid -> {
                if (!valid || !id.equals(entry.event.getId()))
                    return AsyncTask.failed(new IllegalArgumentException("Invalid stored event"));
                return AsyncTask.completed(entry);
            });
        });
    }

    private AsyncTask<Void> initialize() {
        return store.readMetadata("deletions").then(bytes -> {
            if (bytes == null) return null;
            Map<String, Object> history = NGEPlatform.get().fromJSON(new String(bytes, StandardCharsets.UTF_8), Map.class);
            Map<String, Instant> ids = new LinkedHashMap<>();
            Map<String, Instant> addresses = new LinkedHashMap<>();
            restoreHistory(history.get("ids"), ids);
            restoreHistory(history.get("addresses"), addresses);
            deletedIds.clear();
            deletedIds.putAll(ids);
            deletedAddresses.clear();
            deletedAddresses.putAll(addresses);
            trimDeletionHistory();
            return null;
        });
    }

    private static void restoreHistory(Object raw, Map<String, Instant> destination) {
        if (!(raw instanceof Map)) throw new IllegalArgumentException("Malformed cache deletion history");
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))
                throw new IllegalArgumentException("Malformed cache deletion record");
            destination.put((String) entry.getKey(), Instant.parse((String) entry.getValue()));
        }
    }

    private AsyncTask<Void> saveDeletionHistory() {
        Map<String, Object> history = new LinkedHashMap<>();
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> addresses = new LinkedHashMap<>();
        deletedIds.forEach((id, instant) -> ids.put(id, instant.toString()));
        deletedAddresses.forEach((address, instant) -> addresses.put(address, instant.toString()));
        history.put("ids", ids);
        history.put("addresses", addresses);
        return store.writeMetadata("deletions", NGEPlatform.get().toJSON(history).getBytes(StandardCharsets.UTF_8));
    }

    private <T> AsyncTask<T> submit(Supplier<AsyncTask<T>> operation) {
        return AsyncTask.create((resolve, reject) -> operations.enqueue((release, rejectQueue) -> executor.run(() -> {
            if (initialized) return AsyncTask.<Void>completed(null);
            return initialize().then(ignored -> { initialized = true; return null; });
        }).compose(task -> task).compose(ignored -> operation.get()).then(value -> {
            release.accept(null);
            executor.run(() -> { resolve.accept(value); return null; });
            return null;
        }).catchException(failure -> {
            release.accept(null);
            executor.run(() -> { reject.accept(failure); return null; });
        })));
    }

    /**
     * Sets how long events are kept. Null disables this time limit.
     */
    public AsyncTask<Void> setTtl(Duration ttl) {
        if (ttl != null && ttl.isNegative()) throw new IllegalArgumentException("Negative cache TTL");
        return submit(() -> { this.ttl = ttl; return cleanEntries().then(ignored -> null); });
    }

    /**
     * Returns the configured event lifetime, or null if unlimited.
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * Sets how long replaceable events are kept. The default is 100 milliseconds.
     */
    public AsyncTask<Void> setReplaceableTtl(Duration ttl) {
        if (ttl != null && ttl.isNegative()) throw new IllegalArgumentException("Negative replaceable TTL");
        return submit(() -> { replaceableTtl = ttl; return cleanEntries().then(ignored -> null); });
    }

    /**
     * Returns the configured lifetime for replaceable events.
     */
    public Duration getReplaceableTtl() {
        return replaceableTtl;
    }

    /**
     * Sets how long ephemeral events are kept. Zero excludes them.
     */
    public AsyncTask<Void> setEphemeralTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative()) throw new IllegalArgumentException("Invalid ephemeral TTL");
        return submit(() -> { ephemeralTtl = ttl; return cleanEntries().then(ignored -> null); });
    }

    /**
     * Controls whether author deletion requests hide earlier events.
     */
    public AsyncTask<Void> setHonorDeletions(boolean enabled) {
        return submit(() -> {
            honorDeletions = enabled;
            if (!enabled) return AsyncTask.completed(null);
            return NostrEventStore.query(store, Collections.singletonList(new NostrFilter().withKind(5)),
                id -> readEntry(id).then(entry -> entry == null ? null : entry.event), entry -> true)
                .then(events -> { for (SignedNostrEvent event : events) recordDeletion(event); return null; })
                .compose(ignored -> saveDeletionHistory()).compose(ignored -> cleanEntries().then(count -> null));
        });
    }

    private void trimDeletionHistory() {
        while (deletedIds.size() > deletionCapacity) deletedIds.remove(deletedIds.keySet().iterator().next());
        while (deletedAddresses.size() > deletionCapacity) deletedAddresses.remove(deletedAddresses.keySet().iterator().next());
    }

    private boolean expired(Entry entry, Instant now) {
        SignedNostrEvent event = entry.event;
        if (!event.getExpiration().isAfter(now)) return true;
        Duration lifetime = ttl;
        Duration kindTtl = event.isEphemeral() ? ephemeralTtl
            : event.isReplaceable() || event.isAddressable() ? replaceableTtl : null;
        if (kindTtl != null && (lifetime == null || kindTtl.compareTo(lifetime) < 0)) lifetime = kindTtl;
        return lifetime != null && !entry.received.plus(lifetime).isAfter(now);
    }

    private boolean expired(Metadata entry, Instant now) {
        if (!entry.expiration.isAfter(now)) return true;
        Duration lifetime = ttl;
        Duration kindTtl = entry.isEphemeral() ? ephemeralTtl
            : entry.isReplaceable() || entry.isAddressable() ? replaceableTtl : null;
        if (kindTtl != null && (lifetime == null || kindTtl.compareTo(lifetime) < 0)) lifetime = kindTtl;
        return lifetime != null && !entry.received.plus(lifetime).isAfter(now);
    }

    private boolean deleted(Metadata entry) {
        if (!honorDeletions || entry.kind == 5) return false;
        if (deletedIds.containsKey(entry.author + ":" + entry.id)) return true;
        Instant timestamp = deletedAddresses.get(entry.address());
        return (entry.isReplaceable() || entry.isAddressable()) && timestamp != null && !entry.created.isAfter(timestamp);
    }

    private boolean deleted(SignedNostrEvent event) {
        if (!honorDeletions || event.getKind() == 5) return false;
        String author = event.getPubkey().asHex();
        if (deletedIds.containsKey(author + ":" + event.getId())) return true;
        Instant timestamp = deletedAddresses.get(address(event));
        return (event.isReplaceable() || event.isAddressable()) && timestamp != null && !event.getCreatedAt().isAfter(timestamp);
    }

    private void recordDeletion(SignedNostrEvent event) {
        String author = event.getPubkey().asHex();
        for (List<String> tag : event.getTagRows()) {
            if (tag.size() < 2) continue;
            String value = tag.get(1);
            if ("e".equals(tag.get(0)) && value.matches("[a-f0-9]{64}")) {
                deletedIds.put(author + ":" + value, event.getCreatedAt());
            } else if ("a".equals(tag.get(0))) {
                String[] parts = value.split(":", 3);
                if (parts.length == 3 && parts[1].equals(author)) {
                    Instant previous = deletedAddresses.get(value);
                    if (previous == null || previous.isBefore(event.getCreatedAt())) deletedAddresses.put(value, event.getCreatedAt());
                }
            }
        }
        trimDeletionHistory();
    }

    private AsyncTask<Entry> eligibleEntry(String id) {
        return readEntry(id).compose(entry -> {
            if (entry == null) return AsyncTask.completed(null);
            if (expired(entry, clock.get()) || deleted(entry.event)) return store.removeEntry(id).then(ignored -> null);
            return AsyncTask.completed(entry);
        });
    }

    /**
     * Returns an event by ID if it has not expired or been deleted.
     */
    public AsyncTask<SignedNostrEvent> get(String id) {
        return submit(() -> eligibleEntry(id).then(entry -> entry == null ? null : entry.event));
    }

    /**
     * Checks whether the author has deleted this event.
     */
    public AsyncTask<Boolean> isDeleted(SignedNostrEvent event) {
        return submit(() -> AsyncTask.completed(deleted(event)));
    }

    /**
     * Verifies and saves an event. Saving a duplicate does not extend its lifetime.
     */
    public AsyncTask<SignedNostrEvent> put(String id, SignedNostrEvent event) {
        return put(id, event, true);
    }

    /**
     * Verifies and saves an event, optionally notifying listeners after the write.
     */
    public AsyncTask<SignedNostrEvent> put(String id, SignedNostrEvent event, boolean notify) {
        Objects.requireNonNull(event, "event");
        if (!event.getId().equals(id)) return AsyncTask.failed(new IllegalArgumentException("Cache key does not match event ID"));
        boolean[] inserted = { false };
        return submit(() -> event.verifyAsync().compose(valid -> {
            if (!valid) return AsyncTask.failed(new IllegalArgumentException("Invalid event signature"));
            Instant now = clock.get();
            if (!event.isCurrent(now)) return AsyncTask.completed(null);
            AsyncTask<Void> deletion = AsyncTask.completed(null);
            if (honorDeletions && event.getKind() == 5) {
                recordDeletion(event);
                deletion = saveDeletionHistory();
            }
            return deletion.compose(ignored -> {
                if (deleted(event) || event.isEphemeral() && ephemeralTtl.isZero()) return AsyncTask.completed(null);
                return eligibleEntry(id).compose(previous -> {
                    if (previous != null) return AsyncTask.completed(previous.event);
                    return store.writeEntry(id, new Entry(event, now)).then(done -> { inserted[0] = true; return null; });
                });
            });
        })).then(previous -> {
            if (notify && inserted[0]) for (Consumer<SignedNostrEvent> listener : listeners) {
                try { listener.accept(event); }
                catch (Exception failure) { logger.log(Level.WARNING, "Cache observer failed", failure); }
            }
            return previous;
        });
    }

    /**
     * Removes an event but keeps saved deletion requests.
     */
    public AsyncTask<SignedNostrEvent> remove(String id) {
        return submit(() -> store.removeEntry(id).then(entry -> entry == null ? null : entry.event));
    }

    /**
     * Removes an event by ID.
     */
    public AsyncTask<SignedNostrEvent> invalidate(String id) {
        return remove(id);
    }

    /**
     * Removes events but keeps deletion records and unrelated files.
     */
    public AsyncTask<Void> clear() {
        return submit(store::clear);
    }

    private AsyncTask<Integer> cleanEntries() {
        return cleanEntries(null, Integer.MAX_VALUE, false);
    }

    private AsyncTask<Integer> cleanEntries(String after, int count, boolean incremental) {
        int[] removed = { 0 };
        return store.maintenanceBatch(after, count).compose(entries -> visit(entries, entry -> {
            if (incremental) cleanAfter = entry.id;
            if (expired(entry, clock.get()) || deleted(entry))
                return store.discardEntry(entry.id).then(ignored -> { removed[0]++; return null; });
            return AsyncTask.completed(null);
        })).then(ignored -> removed[0]);
    }

    /**
     * Checks at most maxEntries events, continuing where the previous pass stopped.
     */
    public AsyncTask<Integer> clean(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("Cleanup batch must be positive");
        return submit(() -> cleanEntries(cleanAfter, maxEntries, true));
    }

    /**
     * Removes expired or author-deleted events.
     */
    public AsyncTask<Integer> clean() {
        return submit(this::cleanEntries);
    }

    /**
     * Removes expired events and returns the number of remaining events.
     */
    public AsyncTask<Integer> size() {
        return submit(() -> cleanEntries().compose(ignored -> store.entryIds().then(List::size)));
    }

    /**
     * Returns matching events from newest to oldest, with a separate limit for each filter.
     */
    public AsyncTask<List<SignedNostrEvent>> query(Collection<NostrFilter> filters) {
        return submit(() -> {
            Duration lifetime = ttl;
            Duration replaceable = replaceableTtl;
            Duration ephemeral = ephemeralTtl;
            boolean honor = honorDeletions;
            Map<String, Instant> ids = new LinkedHashMap<>(deletedIds);
            Map<String, Instant> addresses = new LinkedHashMap<>(deletedAddresses);
            Predicate<Metadata> eligible = entry -> {
                Instant now = clock.get();
                if (!entry.expiration.isAfter(now)) return false;
                Duration age = lifetime;
                Duration kindAge = entry.isEphemeral() ? ephemeral : entry.isReplaceable() || entry.isAddressable() ? replaceable : null;
                if (kindAge != null && (age == null || kindAge.compareTo(age) < 0)) age = kindAge;
                if (age != null && !entry.received.plus(age).isAfter(now)) return false;
                if (!honor || entry.kind == 5) return true;
                if (ids.containsKey(entry.author + ":" + entry.id)) return false;
                Instant deletedAt = addresses.get(entry.address());
                return !(entry.isReplaceable() || entry.isAddressable()) || deletedAt == null || entry.created.isAfter(deletedAt);
            };
            return AsyncTask.completed(eligible);
        }).compose(eligible -> NostrEventStore.query(store, filters, this::get, eligible));
    }

    /**
     * Calls the listener after a new event is saved. Close the returned handle to stop listening.
     */
    public AutoCloseable observe(Consumer<SignedNostrEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }
}
