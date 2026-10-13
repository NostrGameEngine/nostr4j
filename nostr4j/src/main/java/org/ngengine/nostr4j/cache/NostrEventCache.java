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

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncTask;

/**
 * Asynchronous storage of authenticated Nostr events for direct reads and local relays.
 * Implementations enforce event expiration and their configured freshness/deletion policies.
 * A cache result describes local storage only and does not establish network completeness.
 * The asynchronous contract supports VStore backends such as browser IndexedDB without
 * blocking a JavaScript thread. Implementations need not expose a mutable Map view.
 */
public interface NostrEventCache {
    /**
     * Looks up one event by its complete hexadecimal ID, enforcing expiry and deletion rules.
     *
     * @param id complete event ID
     * @return retained event, or null when absent or no longer eligible
     */
    AsyncTask<SignedNostrEvent> get(String id);

    /**
     * Authenticates and retains an event, notifying observers only for a new eligible insertion.
     * Duplicate insertion must not extend the original freshness lifetime.
     *
     * @param id key that must equal the event's ID
     * @param event signed event to retain
     * @return previously retained duplicate, or null if no previous event was retained
     */
    default AsyncTask<SignedNostrEvent> put(String id, SignedNostrEvent event) {
        return put(id, event, true);
    }

    /**
     * Inserts an event with explicit control over live observer notifications.
     * Network capture suppresses synthetic notifications to preserve the original relay's
     * stored/live delivery metadata. Successful completion includes the backing write.
     *
     * @param id key that must equal the event's ID
     * @param event signed event to authenticate and retain
     * @param notify whether a new insertion should notify observers
     * @return previously retained duplicate, or null if no previous event was retained
     */
    AsyncTask<SignedNostrEvent> put(String id, SignedNostrEvent event, boolean notify);

    /**
     * Removes a payload without erasing author-deletion records.
     *
     * @param id event ID to invalidate
     * @return removed event, or null when no payload existed
     */
    AsyncTask<SignedNostrEvent> remove(String id);

    /**
     * Invalidates one payload using the same semantics as remove.
     *
     * @param id event ID to invalidate
     * @return removed event, or null when no payload existed
     */
    default AsyncTask<SignedNostrEvent> invalidate(String id) {
        return remove(id);
    }

    /**
     * Removes retained payloads while preserving bounded deletion history.
     * The supplied backing store and unrelated entries remain owned by the caller.
     *
     * @return task completing after payload removal
     */
    AsyncTask<Void> clear();

    /**
     * Physically prunes expired and author-deleted payloads.
     * Ordinary reads enforce eligibility even when no maintenance pass has run.
     *
     * @return number of payloads removed
     */
    AsyncTask<Integer> clean();

    /**
     * Counts eligible retained payloads after pruning stale entries.
     *
     * @return number of retained payloads, including cold backing-store entries
     */
    AsyncTask<Integer> size();

    /**
     * Selects local events using per-filter limits, newest-first ordering and ID tie breaking.
     * General queries select the latest replaceable/addressable versions. Explicit ID queries
     * can return older retained versions. Relay-specific search filters are not answered locally.
     * Concurrent writes need not be included in an already-running query.
     *
     * @param filters filters whose results are combined without duplicate IDs
     * @return immutable list of local matches
     */
    AsyncTask<List<SignedNostrEvent>> query(Collection<NostrFilter> filters);

    /**
     * Checks whether an enabled, authenticated author-deletion record suppresses an event.
     *
     * @param event event whose deletion status is checked
     * @return whether a retained deletion applies
     */
    AsyncTask<Boolean> isDeleted(SignedNostrEvent event);

    /**
     * Observes newly retained events after storage completes, outside storage critical sections.
     * Duplicates and removals do not emit insertion notifications.
     *
     * @param listener callback for each new eligible insertion
     * @return handle that unregisters this callback without closing the cache or store
     */
    AutoCloseable observe(Consumer<SignedNostrEvent> listener);
}
