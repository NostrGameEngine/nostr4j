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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.nip50.NostrSearchFilter;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ExecutionQueue;
import org.ngengine.platform.NGEPlatform;

/**
 * Shared freshness, authentication and query policy for the built-in cache backends.
 * Storage operations are serialized through the platform's asynchronous queue; no monitor is
 * held while waiting for IO. Filter callbacks and insertion observers run outside that queue.
 * Configuration belongs to this instance, while a persistent backend retains receipt times
 * and deletion history across instances. Use one cache instance per backing-store namespace.
 */
public abstract class AbstractNostrEventCache implements NostrEventCache {
    protected static final class Entry {
        final SignedNostrEvent event;
        final Instant received;

        Entry(SignedNostrEvent event, Instant received) {
            this.event = event;
            this.received = received;
        }
    }

    private static final Logger logger = Logger.getLogger(AbstractNostrEventCache.class.getName());
    protected final Map<String, Instant> deletedIds = new LinkedHashMap<>();
    protected final Map<String, Instant> deletedAddresses = new LinkedHashMap<>();
    protected final Supplier<Instant> clock;
    private final List<Consumer<SignedNostrEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor(AbstractNostrEventCache.class);
    private final ExecutionQueue operations = NGEPlatform.get().newExecutionQueue(executor);
    private final Runnable closer = NGEPlatform.get().registerFinalizer(this, executor::close);
    private boolean initialized;
    private int deletionCapacity;
    private volatile Duration ttl;
    private volatile Duration replaceableTtl = Duration.ofMillis(100);
    private volatile Duration ephemeralTtl = Duration.ZERO;
    private volatile boolean honorDeletions;

    protected AbstractNostrEventCache(int deletionCapacity, Supplier<Instant> clock) {
        if (deletionCapacity < 1) throw new IllegalArgumentException("Deletion capacity must be positive");
        this.deletionCapacity = deletionCapacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    protected abstract AsyncTask<Entry> readEntry(String id);
    protected abstract AsyncTask<Void> writeEntry(String id, Entry entry);
    protected abstract AsyncTask<Entry> removeEntry(String id);
    protected abstract AsyncTask<List<String>> entryIds();
    protected abstract AsyncTask<Void> clearEntries();

    protected AsyncTask<Void> initialize() {
        return AsyncTask.completed(null);
    }

    protected AsyncTask<Void> saveDeletionHistory() {
        return AsyncTask.completed(null);
    }

    protected final <T> AsyncTask<T> submit(Supplier<AsyncTask<T>> operation) {
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
     * Sets the global maximum age since first insertion and prunes older payloads.
     * Null disables this freshness limit; event expiration and event-kind limits still apply.
     *
     * @param ttl non-negative lifetime, or null for no global freshness limit
     * @return task completing after the policy change and pruning pass
     */
    public AsyncTask<Void> setTtl(Duration ttl) {
        if (ttl != null && ttl.isNegative()) throw new IllegalArgumentException("Negative cache TTL");
        return submit(() -> { this.ttl = ttl; return cleanEntries().then(ignored -> null); });
    }

    /**
     * Returns the current global freshness limit without queueing storage work.
     *
     * @return configured lifetime, or null when disabled
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * Sets the freshness limit for replaceable and addressable events, initially 100 milliseconds.
     * A shorter global TTL or event expiration takes precedence.
     *
     * @param ttl non-negative lifetime, or null to disable the event-kind limit
     * @return task completing after the policy change and pruning pass
     */
    public AsyncTask<Void> setReplaceableTtl(Duration ttl) {
        if (ttl != null && ttl.isNegative()) throw new IllegalArgumentException("Negative replaceable TTL");
        return submit(() -> { replaceableTtl = ttl; return cleanEntries().then(ignored -> null); });
    }

    /**
     * Returns the current freshness limit for replaceable and addressable events.
     *
     * @return configured lifetime, or null when disabled
     */
    public Duration getReplaceableTtl() {
        return replaceableTtl;
    }

    /**
     * Sets the ephemeral-event lifetime; zero, the default, excludes ephemeral payloads.
     *
     * @param ttl non-null, non-negative lifetime
     * @return task completing after the policy change and pruning pass
     */
    public AsyncTask<Void> setEphemeralTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative()) throw new IllegalArgumentException("Invalid ephemeral TTL");
        return submit(() -> { ephemeralTtl = ttl; return cleanEntries().then(ignored -> null); });
    }

    /**
     * Enables or disables NIP-09 author-deletion handling.
     * Enabling processes retained kind-5 payloads and prunes events they suppress.
     * Deletion history remains bounded and survives ordinary payload clearing.
     *
     * @param enabled whether author deletions should be enforced
     * @return task completing after retained deletion events have been processed
     */
    public AsyncTask<Void> setHonorDeletions(boolean enabled) {
        return submit(() -> {
            honorDeletions = enabled;
            if (!enabled) return AsyncTask.completed(null);
            return entryIds().compose(ids -> visit(ids, id -> readEntry(id).then(entry -> {
                if (entry != null && entry.event.getKind() == 5) recordDeletion(entry.event);
                return null;
            }))).compose(ignored -> saveDeletionHistory()).compose(ignored -> cleanEntries().then(count -> null));
        });
    }

    protected final void trimDeletionHistory(int capacity) {
        deletionCapacity = capacity;
        while (deletedIds.size() > capacity) deletedIds.remove(deletedIds.keySet().iterator().next());
        while (deletedAddresses.size() > capacity) deletedAddresses.remove(deletedAddresses.keySet().iterator().next());
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
        trimDeletionHistory(deletionCapacity);
    }

    private AsyncTask<Entry> eligibleEntry(String id) {
        return readEntry(id).compose(entry -> {
            if (entry == null) return AsyncTask.completed(null);
            if (expired(entry, clock.get()) || deleted(entry.event)) return removeEntry(id).then(ignored -> null);
            return AsyncTask.completed(entry);
        });
    }

    @Override
    public AsyncTask<SignedNostrEvent> get(String id) {
        return submit(() -> eligibleEntry(id).then(entry -> entry == null ? null : entry.event));
    }

    @Override
    public AsyncTask<Boolean> isDeleted(SignedNostrEvent event) {
        return submit(() -> AsyncTask.completed(deleted(event)));
    }

    @Override
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
                    return writeEntry(id, new Entry(event, now)).then(done -> { inserted[0] = true; return null; });
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

    @Override
    public AsyncTask<SignedNostrEvent> remove(String id) {
        return submit(() -> removeEntry(id).then(entry -> entry == null ? null : entry.event));
    }

    @Override
    public AsyncTask<Void> clear() {
        return submit(this::clearEntries);
    }

    private AsyncTask<Integer> cleanEntries() {
        int[] removed = { 0 };
        return entryIds().compose(ids -> visit(ids, id -> readEntry(id).compose(entry -> {
            if (entry != null && (expired(entry, clock.get()) || deleted(entry.event))) {
                return removeEntry(id).then(ignored -> { removed[0]++; return null; });
            }
            return AsyncTask.completed(null);
        }))).then(ignored -> removed[0]);
    }

    @Override
    public AsyncTask<Integer> clean() {
        return submit(this::cleanEntries);
    }

    @Override
    public AsyncTask<Integer> size() {
        return submit(() -> cleanEntries().compose(ignored -> entryIds().then(List::size)));
    }

    private static String address(SignedNostrEvent event) {
        String identifier = event.isAddressable() ? event.getFirstTagFirstValue("d") : "";
        return event.getKind() + ":" + event.getPubkey().asHex() + ":" + (identifier == null ? "" : identifier);
    }

    private static final Comparator<SignedNostrEvent> ORDER = Comparator
        .comparing(SignedNostrEvent::getCreatedAt).reversed().thenComparing(SignedNostrEvent::getId);

    @Override
    public AsyncTask<List<SignedNostrEvent>> query(Collection<NostrFilter> filters) {
        List<NostrFilter> snapshot = new ArrayList<>(filters);
        if (snapshot.isEmpty()) return AsyncTask.completed(Collections.emptyList());
        Set<String> explicit = new LinkedHashSet<>();
        boolean idsOnly = true;
        for (NostrFilter filter : snapshot) {
            if (filter.getIds() == null) idsOnly = false;
            else explicit.addAll(filter.getIds());
        }
        AsyncTask<List<String>> ids = idsOnly ? AsyncTask.completed(new ArrayList<>(explicit)) : submit(this::entryIds);
        List<SignedNostrEvent> candidates = new ArrayList<>();
        Map<String, SignedNostrEvent.Identifier> latest = new LinkedHashMap<>();
        return ids.compose(keys -> visit(keys, id -> get(id).then(event -> {
            if (event == null) return null;
            if (event.isReplaceable() || event.isAddressable()) {
                String key = address(event);
                SignedNostrEvent.Identifier previous = latest.get(key);
                if (previous == null || event.getCreatedAt().isAfter(previous.createdAtInstant)
                        || event.getCreatedAt().equals(previous.createdAtInstant) && event.getId().compareTo(previous.id) < 0)
                    latest.put(key, event.getIdentifier());
            }
            for (NostrFilter filter : snapshot) if (!(filter instanceof NostrSearchFilter) && filter.matches(event, 0)) {
                candidates.add(event);
                break;
            }
            return null;
        }))).then(ignored -> {
            candidates.sort(ORDER);
            Set<String> selected = new LinkedHashSet<>();
            for (NostrFilter filter : snapshot) {
                if (filter instanceof NostrSearchFilter) continue;
                int count = 0;
                for (SignedNostrEvent event : candidates) {
                    if (filter.getIds() == null && (event.isReplaceable() || event.isAddressable())
                            && !latest.get(address(event)).id.equals(event.getId())) continue;
                    if (filter.matches(event, count)) { selected.add(event.getId()); count++; }
                }
            }
            candidates.removeIf(event -> !selected.contains(event.getId()));
            return Collections.unmodifiableList(candidates);
        });
    }

    protected final <T> AsyncTask<Void> visit(List<T> values, java.util.function.Function<T, AsyncTask<?>> action) {
        AsyncTask<Void> task = AsyncTask.completed(null);
        for (T value : values) task = task.compose(ignored -> action.apply(value).then(done -> null));
        return task;
    }

    @Override
    public AutoCloseable observe(Consumer<SignedNostrEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }
}
