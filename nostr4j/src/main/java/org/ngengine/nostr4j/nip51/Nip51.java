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

package org.ngengine.nostr4j.nip51;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

/**
 * Read and update NIP-51 bookmark lists through the normal pool APIs.
 * Bookmark data and event construction belong to Nip51BookmarkList.
 * Relay selection, acknowledgement policy and merging concurrent changes remain caller-controlled.
 */
public final class Nip51 {

    public static final int BOOKMARKS = 10003;
    public static final int BOOKMARK_SET = 30003;

    private Nip51() {}

    /**
     * Copy a bookmark event into an editable list, retaining metadata and encrypted content.
     * Remote event signatures must be verified before trusting public references.
     *
     * @param event the global bookmark list or named bookmark set
     * @return an independent bookmark model
     */
    public static Nip51BookmarkList from(NostrEvent event) {
        return new Nip51BookmarkList(event);
    }

    /**
     * Fetch an author's global bookmark list using the default metadata timeout.
     * A missing list is represented by an empty editable global list.
     *
     * @param pool the pool to query
     * @param author the bookmark list author
     * @return a task resolving to the bookmark model
     */
    public static AsyncTask<Nip51BookmarkList> fetch(NostrPool pool, NostrPublicKey author) {
        return fetch(pool, author, Duration.ofSeconds(21));
    }

    /**
     * Fetch an author's global bookmark list with an explicit timeout.
     * Relay selection and fetch scheduling follow the pool's existing configuration.
     *
     * @param pool the pool to query
     * @param author the bookmark list author
     * @param timeout the maximum query duration
     * @return a task resolving to the bookmark model
     */
    public static AsyncTask<Nip51BookmarkList> fetch(NostrPool pool, NostrPublicKey author, Duration timeout) {
        NostrFilter filter = new NostrFilter()
            .withKind(BOOKMARKS)
            .withAuthor(Objects.requireNonNull(author, "author"))
            .limit(1);
        return fetch(pool, filter, timeout).then(bookmarks -> bookmarks == null ? new Nip51BookmarkList() : bookmarks);
    }

    /**
     * Fetch a bookmark list selected by a caller-supplied filter, including named sets.
     * A missing event yields null so the caller can choose the intended kind and set identifier.
     *
     * @param pool the pool to query
     * @param filter the bookmark query, including author, kind and any set identifier
     * @param timeout the maximum query duration
     * @return a task resolving to the bookmark model, or null if no event matches
     */
    public static AsyncTask<Nip51BookmarkList> fetch(NostrPool pool, NostrFilter filter, Duration timeout) {
        return pool.fetch(filter, 1, timeout).then(events -> events.isEmpty() ? null : from(events.get(0)));
    }

    /**
     * Sign and publish a complete bookmark list using the pool's acknowledgement policy.
     * Private content must belong to the signing author; the model is copied before asynchronous work starts.
     *
     * @param pool the pool to publish through
     * @param signer the bookmark author
     * @param bookmarks the complete replacement list
     * @return the pool's relay acknowledgement tasks
     */
    public static AsyncTask<List<AsyncTask<NostrMessageAck>>> update(
        NostrPool pool,
        NostrSigner signer,
        Nip51BookmarkList bookmarks
    ) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(signer, "signer");
        return Objects
            .requireNonNull(bookmarks, "bookmarks")
            .prepareUpdate(signer)
            .compose(signer::sign)
            .compose(pool::publish);
    }

    /**
     * Build a bookmark reference to an event ID or an addressable event coordinate.
     * Ordinary replaceable events retain ID references rather than address references.
     *
     * @param event the event to bookmark
     * @return an immutable reference tag
     */
    public static List<String> reference(SignedNostrEvent event) {
        return event.isAddressable() && event.getFirstTagFirstValue("d") != null
            ? List.of("a", event.getCoordinates().coords())
            : List.of("e", event.getId());
    }
}
