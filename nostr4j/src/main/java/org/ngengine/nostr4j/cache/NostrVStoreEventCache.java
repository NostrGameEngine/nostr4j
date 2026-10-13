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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;

/**
 * Write-through event cache with a bounded memory tier and caller-supplied VStore backing.
 * Payloads use nostr-event-cache/v1/events/ab/cd/&lt;id&gt;.json, so full-ID reads require no
 * global payload index. Filter queries enumerate keys and read payloads incrementally;
 * memory use includes matching results and temporary replaceable-version metadata.
 * Every cold payload is authenticated before use. Receipt times and bounded deletion
 * history survive reopening; freshness settings remain local to each cache instance.
 * Only one instance may write a namespace at a time. The supplied store is never closed.
 */
public final class NostrVStoreEventCache extends AbstractNostrEventCache {
    private static final String ROOT = "nostr-event-cache/v1/";
    private static final String EVENTS = ROOT + "events/";
    private static final String HISTORY = ROOT + "deletions.json";
    private static final int DELETION_CAPACITY = 4096;
    private final VStore store;
    private final int memoryCapacity;
    private final Map<String, Entry> hot = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Creates a persistent cache using the system clock and a bounded memory tier.
     * The total backing-store payload count is independent of the memory capacity;
     * expiration, explicit invalidation and clear reclaim persistent payloads.
     *
     * @param store caller-owned store, whose cache namespace has one active writer
     * @param memoryCapacity non-negative maximum number of hot payloads; zero disables the memory tier
     */
    public NostrVStoreEventCache(VStore store, int memoryCapacity) {
        this(store, memoryCapacity, Instant::now);
    }

    /**
     * Creates a persistent cache with an explicit clock.
     * Successful insertions include the VStore write; memory eviction never discards
     * an unwritten payload. Up to 4096 records are retained in each deletion history.
     *
     * @param store caller-owned backing store
     * @param memoryCapacity non-negative number of payloads retained in memory
     * @param clock source of the current time for freshness and expiry checks
     */
    public NostrVStoreEventCache(VStore store, int memoryCapacity, Supplier<Instant> clock) {
        super(DELETION_CAPACITY, clock);
        if (memoryCapacity < 0) throw new IllegalArgumentException("Memory capacity cannot be negative");
        this.store = Objects.requireNonNull(store, "store");
        this.memoryCapacity = memoryCapacity;
    }

    private static String path(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid event ID");
        return EVENTS + id.substring(0, 2) + "/" + id.substring(2, 4) + "/" + id + ".json";
    }

    private void remember(String id, Entry entry) {
        if (memoryCapacity == 0) return;
        hot.put(id, entry);
        while (hot.size() > memoryCapacity) hot.remove(hot.keySet().iterator().next());
    }

    @Override
    protected AsyncTask<Entry> readEntry(String id) {
        String path = path(id);
        Entry memory = hot.get(id);
        if (memory != null) return AsyncTask.completed(memory);
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
                    remember(id, entry);
                    return AsyncTask.completed(entry);
                });
            });
        });
    }

    @Override
    protected AsyncTask<Void> writeEntry(String id, Entry entry) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("received", entry.received.toString());
        envelope.put("event", entry.event.toMap());
        byte[] bytes = NGEPlatform.get().toJSON(envelope).getBytes(StandardCharsets.UTF_8);
        return store.writeFully(path(id), bytes).then(ignored -> { remember(id, entry); return null; });
    }

    @Override
    protected AsyncTask<Entry> removeEntry(String id) {
        return readEntry(id).compose(previous -> store.delete(path(id)).then(ignored -> {
            hot.remove(id);
            return previous;
        }));
    }

    @Override
    protected AsyncTask<List<String>> entryIds() {
        return store.listAll().then(paths -> {
            List<String> ids = new ArrayList<>();
            for (String stored : paths) {
                String normalized = stored.replace('\\', '/');
                if (!normalized.startsWith(EVENTS) || !normalized.endsWith(".json")) continue;
                String id = normalized.substring(normalized.lastIndexOf('/') + 1, normalized.length() - 5);
                if (id.matches("[a-f0-9]{64}") && path(id).equals(normalized)) ids.add(id);
            }
            return ids;
        });
    }

    @Override
    protected AsyncTask<Void> clearEntries() {
        return entryIds().compose(ids -> visit(ids, id -> store.delete(path(id))
            .then(ignored -> { hot.remove(id); return null; }))).then(ignored -> { hot.clear(); return null; });
    }

    @Override
    protected AsyncTask<Void> initialize() {
        return store.exists(HISTORY).compose(exists -> {
            if (!exists) return AsyncTask.completed(null);
            return store.readFully(HISTORY).then(bytes -> {
                Map<String, Object> history = NGEPlatform.get().fromJSON(new String(bytes, StandardCharsets.UTF_8), Map.class);
                Map<String, Instant> ids = new LinkedHashMap<>();
                Map<String, Instant> addresses = new LinkedHashMap<>();
                restoreHistory(history.get("ids"), ids);
                restoreHistory(history.get("addresses"), addresses);
                deletedIds.clear();
                deletedIds.putAll(ids);
                deletedAddresses.clear();
                deletedAddresses.putAll(addresses);
                trimDeletionHistory(DELETION_CAPACITY);
                return null;
            });
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

    @Override
    protected AsyncTask<Void> saveDeletionHistory() {
        Map<String, Object> history = new LinkedHashMap<>();
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> addresses = new LinkedHashMap<>();
        deletedIds.forEach((id, instant) -> ids.put(id, instant.toString()));
        deletedAddresses.forEach((address, instant) -> addresses.put(address, instant.toString()));
        history.put("ids", ids);
        history.put("addresses", addresses);
        return store.writeFully(HISTORY, NGEPlatform.get().toJSON(history).getBytes(StandardCharsets.UTF_8));
    }
}
