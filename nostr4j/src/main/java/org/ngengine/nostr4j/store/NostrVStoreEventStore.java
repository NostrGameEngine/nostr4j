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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ExecutionQueue;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;

/**
 * Saves events in a VStore and keeps recent reads in memory.
 */
public final class NostrVStoreEventStore implements NostrEventStore {
    private static final String ROOT = "nostr-event-cache/v1/";
    private static final String EVENTS = ROOT + "events/";
    private static final String INDEX = ROOT + "index/";
    private static final String STATE = INDEX + "state";
    private static final String DIRTY = INDEX + "dirty";
    private NostrEventIndex index;
    private String revision;
    private final VStore store;
    private final NostrInMemoryEventStore hot;
    private final AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor(NostrVStoreEventStore.class);
    private final ExecutionQueue operations = NGEPlatform.get().newExecutionQueue(executor);
    private final Runnable closer = NGEPlatform.get().registerFinalizer(this, executor::close);

    public NostrVStoreEventStore(VStore store, int memoryCapacity) {
        if (memoryCapacity < 0) throw new IllegalArgumentException("Memory capacity cannot be negative");
        this.store = Objects.requireNonNull(store, "store");
        this.hot = memoryCapacity == 0 ? null : new NostrInMemoryEventStore(memoryCapacity);
    }

    private <T> AsyncTask<T> submit(Supplier<AsyncTask<T>> operation) {
        return AsyncTask.create((resolve, reject) -> operations.enqueue((release, rejectQueue) -> executor.run(operation::get)
            .compose(task -> task).then(value -> {
                release.accept(null);
                executor.run(() -> { resolve.accept(value); return null; });
                return null;
            }).catchException(failure -> {
                index = null;
                revision = null;
                release.accept(null);
                executor.run(() -> { reject.accept(failure); return null; });
            })));
    }

    private static String path(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid event ID");
        return EVENTS + id.substring(0, 2) + "/" + id.substring(2, 4) + "/" + id + ".json";
    }

    private static String eventId(String path) {
        if (!path.startsWith(EVENTS) || !path.endsWith(".json")) return null;
        String id = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
        return id.matches("[a-f0-9]{64}") && path(id).equals(path) ? id : null;
    }

    private static boolean indexBucket(String path) {
        return path.matches(INDEX + "[a-f0-9]{2}\\.json");
    }

    private AsyncTask<Void> remember(String id, Entry entry) {
        return hot == null ? AsyncTask.completed(null) : hot.writeEntry(id, entry);
    }

    private AsyncTask<Entry> read(String id) { return read(id, true); }

    private AsyncTask<Entry> read(String id, boolean remember) {
        String path = path(id);
        AsyncTask<Entry> memory = hot == null ? AsyncTask.completed(null) : hot.readEntry(id);
        return memory.compose(cached -> {
            if (cached != null) return AsyncTask.completed(cached);
            return store.exists(path).compose(exists -> {
                if (!exists) return AsyncTask.completed(null);
                return store.readFully(path).compose(bytes -> {
                    Map<String, Object> raw = NGEPlatform.get().fromJSON(new String(bytes, StandardCharsets.UTF_8), Map.class);
                    if (!(raw.get("event") instanceof Map) || !(raw.get("received") instanceof String))
                        return AsyncTask.failed(new IllegalArgumentException("Malformed cached event envelope"));
                    SignedNostrEvent event = new SignedNostrEvent((Map<String, Object>) raw.get("event"));
                    Instant received = Instant.parse((String) raw.get("received"));
                    return event.verifyAsync().compose(valid -> {
                        if (!valid || !id.equals(event.getId())) return AsyncTask.failed(new IllegalArgumentException("Invalid cached event"));
                        Entry entry = new Entry(event, received);
                        return (remember ? remember(id, entry) : AsyncTask.<Void>completed(null)).then(ignored -> entry);
                    });
                });
            });
        });
    }

    private AsyncTask<Void> write(String id, Entry entry) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("received", entry.received.toString());
        envelope.put("event", entry.event.toMap());
        byte[] bytes = NGEPlatform.get().toJSON(envelope).getBytes(StandardCharsets.UTF_8);
        return store.writeFully(path(id), bytes).compose(ignored -> remember(id, entry));
    }

    private AsyncTask<Entry> remove(String id) {
        return read(id).compose(previous -> {
            if (previous == null) return AsyncTask.completed(null);
            return markDirty().compose(ignored -> store.delete(path(id)))
                .compose(ignored -> hot == null ? AsyncTask.completed(null) : hot.removeEntry(id))
                .compose(ignored -> updateBucket(id, null)).then(ignored -> previous);
        });
    }

    @Override
    public AsyncTask<Entry> readEntry(String id) {
        return submit(() -> read(id));
    }

    @Override
    public AsyncTask<Void> writeEntry(String id, Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!entry.event.getId().equals(id)) return AsyncTask.failed(new IllegalArgumentException("Store key does not match event ID"));
        return submit(() -> read(id).compose(previous -> {
            if (previous != null) return AsyncTask.completed(null);
            return markDirty().compose(ignored -> write(id, entry))
                .compose(ignored -> updateBucket(id, new NostrEventIndex.Metadata(entry)));
        }));
    }

    @Override
    public AsyncTask<Entry> removeEntry(String id) {
        return submit(() -> remove(id));
    }

    @Override
    public AsyncTask<Void> discardEntry(String id) {
        return submit(() -> store.exists(path(id)).compose(exists -> {
            if (!exists) return AsyncTask.completed(null);
            return markDirty().compose(ignored -> store.delete(path(id)))
                .compose(ignored -> hot == null ? AsyncTask.completed(null) : hot.removeEntry(id))
                .compose(ignored -> updateBucket(id, null));
        }));
    }

    @Override
    public AsyncTask<List<String>> entryIds() {
        return submit(() -> ensureIndex().then(current -> new ArrayList<>(current.ids())));
    }

    @Override
    public AsyncTask<Void> clear() {
        return submit(() -> markDirty().compose(ignored -> store.listAll()).compose(paths -> {
            List<String> owned = new ArrayList<>();
            for (String path : paths) {
                String normalized = path.replace('\\', '/');
                if (eventId(normalized) != null || indexBucket(normalized)) owned.add(path);
            }
            return NostrEventStore.visit(owned, store::delete);
        }).compose(ignored -> hot == null ? AsyncTask.completed(null) : hot.clear()).compose(ignored -> {
            index = new NostrEventIndex();
            return publishRevision();
        }));
    }

    @Override
    public AsyncTask<Iterator<NostrEventIndex.Metadata>> candidates(NostrFilter filter, Predicate<NostrEventIndex.Metadata> eligible) {
        return submit(() -> ensureIndex().then(current -> current.candidates(filter, eligible)));
    }

    @Override
    public AsyncTask<List<NostrEventIndex.Metadata>> maintenanceBatch(String after, int count) {
        return submit(() -> ensureIndex().then(current -> current.batch(after, count)));
    }

    private AsyncTask<byte[]> optional(String path) {
        return store.exists(path).compose(exists -> exists ? store.readFully(path) : AsyncTask.completed(null));
    }

    private AsyncTask<Void> markDirty() {
        return store.writeFully(DIRTY, new byte[] { 1 });
    }

    private AsyncTask<Void> publishRevision() {
        String next = UUID.randomUUID().toString();
        return store.writeFully(STATE, next.getBytes(StandardCharsets.UTF_8))
            .compose(ignored -> store.delete(DIRTY)).then(ignored -> { revision = next; return null; });
    }

    private List<NostrEventIndex.Metadata> decodeBucket(byte[] bytes) {
        List<?> raw = NGEPlatform.get().fromJSON(new String(bytes, StandardCharsets.UTF_8), List.class);
        List<NostrEventIndex.Metadata> result = new ArrayList<>();
        for (Object item : raw) result.add(NostrEventIndex.Metadata.fromMap((Map<String, Object>) item));
        return result;
    }

    private AsyncTask<Void> saveBucket(String prefix, Collection<NostrEventIndex.Metadata> entries) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (NostrEventIndex.Metadata entry : entries) raw.add(entry.toMap());
        return store.writeFully(INDEX + prefix + ".json", NGEPlatform.get().toJSON(raw).getBytes(StandardCharsets.UTF_8));
    }

    private AsyncTask<Void> updateBucket(String id, NostrEventIndex.Metadata entry) {
        String prefix = id.substring(0, 2);
        AsyncTask<List<NostrEventIndex.Metadata>> previous;
        if (index != null) {
            if (entry == null) index.remove(id); else index.put(entry);
            previous = AsyncTask.completed(index.bucket(prefix));
        } else {
            previous = optional(INDEX + prefix + ".json").then(bytes -> {
                List<NostrEventIndex.Metadata> values = new ArrayList<>();
                if (bytes != null) {
                    try { values.addAll(decodeBucket(bytes)); }
                    catch (RuntimeException malformed) { /* The next query will rebuild missing entries. */ }
                }
                values.removeIf(value -> value.id.equals(id));
                if (entry != null) values.add(entry);
                return values;
            });
        }
        return previous.compose(values -> saveBucket(prefix, values)).compose(ignored -> publishRevision());
    }

    private AsyncTask<NostrEventIndex> ensureIndex() {
        return store.exists(DIRTY).compose(dirty -> optional(STATE).compose(state -> {
            String storedRevision = state == null ? null : new String(state, StandardCharsets.UTF_8);
            if (!dirty && index != null && Objects.equals(revision, storedRevision)) return AsyncTask.completed(index);
            return store.listAll().compose(paths -> {
                Set<String> payloadIds = new HashSet<>();
                List<String> buckets = new ArrayList<>();
                for (String raw : paths) {
                    String normalized = raw.replace('\\', '/');
                    String id = eventId(normalized);
                    if (id != null) payloadIds.add(id);
                    else if (indexBucket(normalized)) buckets.add(normalized);
                }
                if (dirty || state == null) return rebuild(payloadIds);
                NostrEventIndex loaded = new NostrEventIndex();
                boolean[] valid = { true };
                return NostrEventStore.visit(buckets, bucket -> store.readFully(bucket).then(bytes -> {
                    try {
                        for (NostrEventIndex.Metadata entry : decodeBucket(bytes)) {
                            if (!bucket.equals(INDEX + entry.id.substring(0, 2) + ".json") || loaded.get(entry.id) != null) valid[0] = false;
                            loaded.put(entry);
                        }
                    } catch (RuntimeException malformed) { valid[0] = false; }
                    return null;
                })).compose(ignored -> {
                    if (!valid[0] || !loaded.ids().equals(payloadIds)) return rebuild(payloadIds);
                    index = loaded;
                    revision = storedRevision;
                    return AsyncTask.completed(index);
                });
            });
        }));
    }

    /**
     * Recreates the index from saved events after an interrupted write or an old store.
     */
    private AsyncTask<NostrEventIndex> rebuild(Set<String> ids) {
        NostrEventIndex rebuilt = new NostrEventIndex();
        return markDirty().compose(ignored -> NostrEventStore.visit(new ArrayList<>(ids), id -> read(id, false).then(entry -> {
            if (entry != null) rebuilt.put(new NostrEventIndex.Metadata(entry));
            return null;
        }))).compose(ignored -> store.listAll()).compose(paths -> {
            List<String> old = new ArrayList<>();
            for (String path : paths) if (indexBucket(path.replace('\\', '/'))) old.add(path);
            return NostrEventStore.visit(old, store::delete);
        }).compose(ignored -> {
            Set<String> prefixes = new HashSet<>();
            for (String id : rebuilt.ids()) prefixes.add(id.substring(0, 2));
            return NostrEventStore.visit(new ArrayList<>(prefixes), prefix -> saveBucket(prefix, rebuilt.bucket(prefix)));
        }).compose(ignored -> publishRevision()).then(ignored -> { index = rebuilt; return index; });
    }

    private static String metadataPath(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid metadata name");
        return ROOT + name + ".json";
    }

    @Override
    public AsyncTask<byte[]> readMetadata(String name) {
        String path = metadataPath(name);
        return submit(() -> store.exists(path).compose(exists -> exists ? store.readFully(path) : AsyncTask.completed(null)));
    }

    @Override
    public AsyncTask<Void> writeMetadata(String name, byte[] value) {
        String path = metadataPath(name);
        byte[] snapshot = value.clone();
        return submit(() -> store.writeFully(path, snapshot));
    }
}
