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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * Editable NIP-51 bookmark data, following the metadata model and toUpdateEvent convention.
 * The event exposes optional metadata through the existing tag API.
 * Private references are encrypted to the author; updates replace the complete list.
 */
public final class Nip51BookmarkList {

    /**
     * Editable event fields and optional metadata, copied when an update is prepared.
     * Use setPrivateBookmarks to replace encrypted content with a known author.
     */
    public final UnsignedNostrEvent event;
    private final NostrEvent sourceEvent;
    private NostrPublicKey privateOwner;

    /**
     * Create an empty global bookmark list.
     * Public and private entries can be supplied independently before updating it.
     */
    public Nip51BookmarkList() {
        this(new UnsignedNostrEvent().withKind(Nip51.BOOKMARKS));
    }

    /**
     * Copy a global list or named set, including unknown metadata and encrypted content.
     * Public references are validated without modifying the source event.
     *
     * @param source the bookmark event to copy
     */
    public Nip51BookmarkList(NostrEvent source) {
        requireBookmarkEvent(Objects.requireNonNull(source, "source"));
        sourceEvent = source;
        if (source instanceof UnsignedNostrEvent) {
            event = ((UnsignedNostrEvent) source).clone();
        } else {
            event =
                new UnsignedNostrEvent()
                    .withKind(source.getKind())
                    .withContent(source.getContent())
                    .createdAt(source.getCreatedAt());
            for (List<String> row : source.getTagRows()) {
                if (!row.isEmpty()) event.withTag(row.get(0), row.subList(1, row.size()));
            }
        }
        if (source instanceof SignedNostrEvent) privateOwner = ((SignedNostrEvent) source).getPubkey();
        getPublicBookmarks();
    }

    /**
     * Return immutable copies of the public references while leaving metadata tags intact.
     * Reference validation includes the shared platform's string size checks.
     *
     * @return the public bookmark tags
     */
    public List<List<String>> getPublicBookmarks() {
        return publicReferences(event);
    }

    /**
     * Replace public references without replacing metadata or encrypted private entries.
     * The supplied collection is validated and copied before the event is modified.
     *
     * @param items the complete public bookmark references
     */
    public void setPublicBookmarks(Collection<List<String>> items) {
        List<List<String>> snapshot = copyReferences(items);
        event.clearTags("e").clearTags("a");
        for (List<String> row : snapshot) event.withTag(row.get(0), row.subList(1, row.size()));
    }

    /**
     * Verify a signed source and decrypt its private references with the owner's signer.
     * NIP-44 is used for current content; legacy NIP-04 lists can still be read.
     *
     * @param signer the private bookmark owner
     * @return immutable private references
     */
    public AsyncTask<List<List<String>>> getPrivateBookmarks(NostrSigner signer) {
        Objects.requireNonNull(signer, "signer");
        requireBookmarkEvent(event);
        String content = event.getContent();
        NostrPublicKey expected = privateOwner;
        AsyncTask<Boolean> verified = sourceEvent instanceof SignedNostrEvent
            ? ((SignedNostrEvent) sourceEvent).verifyAsync()
            : AsyncTask.completed(true);
        return verified.compose(valid -> {
            if (!valid) throw new IllegalArgumentException("Invalid bookmark event signature");
            return signer
                .getPublicKey()
                .compose(owner -> {
                    if (expected != null && !expected.equals(owner)) throw new IllegalArgumentException(
                        "Bookmark signer is not the author"
                    );
                    if (content.isEmpty()) return AsyncTask.completed(List.of());
                    NostrSigner.EncryptAlgo algorithm = content.contains("?iv=")
                        ? NostrSigner.EncryptAlgo.NIP04
                        : NostrSigner.EncryptAlgo.NIP44;
                    return signer
                        .decrypt(content, owner, algorithm)
                        .then(json -> {
                            List<?> rows = NGEUtils.getPlatform().fromJSON(json, List.class);
                            return copyReferences(rows);
                        });
                });
        });
    }

    /**
     * Replace private references using NIP-44 without changing public references or metadata.
     * Inputs are copied before encryption; an empty collection clears the private content.
     *
     * @param signer the author who will publish the list
     * @param items the complete private bookmark references
     * @return a task resolving to this model after encryption completes
     */
    public AsyncTask<Nip51BookmarkList> setPrivateBookmarks(NostrSigner signer, Collection<List<String>> items) {
        Objects.requireNonNull(signer, "signer");
        List<List<String>> snapshot = copyReferences(items);
        String json = NGEUtils.getPlatform().toJSON(snapshot);
        return signer
            .getPublicKey()
            .compose(owner -> {
                AsyncTask<String> content = snapshot.isEmpty()
                    ? AsyncTask.completed("")
                    : signer.encrypt(json, owner, NostrSigner.EncryptAlgo.NIP44);
                return content.then(encrypted -> {
                    event.withContent(encrypted);
                    privateOwner = owner;
                    return this;
                });
            });
    }

    /**
     * Build an independent replacement event with a fresh timestamp.
     * Unknown metadata and untouched encrypted content are retained.
     *
     * @return the unsigned replacement event
     */
    public UnsignedNostrEvent toUpdateEvent() {
        UnsignedNostrEvent snapshot = event.clone();
        publicReferences(snapshot);
        return snapshot.createdAt(Instant.now());
    }

    AsyncTask<UnsignedNostrEvent> prepareUpdate(NostrSigner signer) {
        UnsignedNostrEvent snapshot = toUpdateEvent();
        NostrPublicKey expected = privateOwner;
        if (snapshot.getContent().isEmpty()) return AsyncTask.completed(snapshot);
        if (expected == null) throw new IllegalStateException(
            "Set private bookmarks with their author's signer before publishing"
        );
        return signer
            .getPublicKey()
            .then(owner -> {
                if (!expected.equals(owner)) throw new IllegalArgumentException("Bookmark signer is not the author");
                return snapshot;
            });
    }

    private static List<List<String>> copyReferences(Collection<?> items) {
        for (Object raw : Objects.requireNonNull(items, "items")) {
            if (!(raw instanceof List<?>)) throw new IllegalArgumentException("Invalid bookmark tag");
            for (Object value : (List<?>) raw) {
                if (!(value instanceof String)) throw new IllegalArgumentException("Invalid bookmark value");
            }
        }
        List<List<String>> result = new ArrayList<>();
        for (String[] row : NGEUtils.safeCollectionOfStringArray(items)) {
            List<String> reference = List.of(row);
            validateReference(reference);
            result.add(reference);
        }
        return List.copyOf(result);
    }

    private static List<List<String>> publicReferences(NostrEvent event) {
        requireBookmarkEvent(event);
        List<List<String>> items = new ArrayList<>();
        for (List<String> row : event.getTagRows()) {
            if (!row.isEmpty() && (row.get(0).equals("e") || row.get(0).equals("a"))) items.add(row);
        }
        return copyReferences(items);
    }

    private static void validateReference(List<String> reference) {
        if (reference.size() < 2 || reference.size() > 4) throw new IllegalArgumentException("Invalid bookmark reference");
        String type = reference.get(0), target = reference.get(1);
        if (type.equals("e")) {
            if (!target.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid bookmarked event ID");
        } else if (type.equals("a")) {
            String[] parts = target.split(":", 3);
            if (parts.length != 3 || parts[2].length() > 4096) throw new IllegalArgumentException("Invalid bookmarked address");
            int kind = NGEUtils.safeInt(parts[0]);
            if (kind < 30000 || kind >= 40000 || !parts[0].equals(Integer.toString(kind))) {
                throw new IllegalArgumentException("Invalid bookmarked address kind");
            }
            NostrPublicKey.fromHex(parts[1]);
        } else throw new IllegalArgumentException("Unsupported bookmark reference");
    }

    private static void requireBookmarkEvent(NostrEvent event) {
        if (event.getKind() != Nip51.BOOKMARKS && event.getKind() != Nip51.BOOKMARK_SET) {
            throw new IllegalArgumentException("Not a NIP-51 bookmark list or set");
        }
        if (event.getKind() == Nip51.BOOKMARK_SET && NGEUtils.safeString(event.getFirstTagFirstValue("d")).isBlank()) {
            throw new IllegalArgumentException("Missing bookmark set identifier");
        }
    }
}
