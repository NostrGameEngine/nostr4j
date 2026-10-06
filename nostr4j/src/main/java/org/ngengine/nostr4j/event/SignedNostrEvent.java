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
package org.ngengine.nostr4j.event;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import org.ngengine.bech32.Bech32;
import org.ngengine.bech32.Bech32Exception;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.utils.ImmutableSnapshot;
import org.ngengine.nostr4j.utils.ZeroCounter;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.SafeFlag;

public class SignedNostrEvent extends NostrMessage implements NostrEvent {

    private static final long serialVersionUID = 2541630848810678482L;

    private static final byte[] BECH32_PREVIX = "note".getBytes(StandardCharsets.UTF_8);

    public static class Identifier implements Serializable {

        public final String id;
        public final long createdAt;
        public final Instant createdAtInstant;

        Identifier(String id, Instant createdAt) {
            this.id = id;
            this.createdAt = createdAt.getEpochSecond();
            this.createdAtInstant = createdAt;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof Identifier)) return false;
            if (obj == this) return true;

            Identifier e = (Identifier) obj;
            return e.id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private final int kind;
    private final String content;
    private transient volatile Map<String, List<TagValue>> tags;
    private transient volatile TagLookup firstTagLookup;
    private final List<List<String>> tagRows;
    private final String signature;
    private final String pubkey;
    private final Identifier identifier;
    private final boolean encodingCacheAllowed;

    private transient String bech32Id;
    private transient NostrPublicKey parsedPublicKey;
    private transient Instant expiresAt;
    private transient SafeFlag verificationCached = new SafeFlag(false);
    private transient SafeFlag verificationResult = new SafeFlag(false);
    private transient volatile CachedEncoding eventIdCache;
    private transient volatile CachedEncoding eventJsonCache;

    private static final class CachedEncoding {

        final NGEPlatform platform;
        final String pubkey;
        final String value;

        CachedEncoding(NGEPlatform platform, String pubkey, String value) {
            this.platform = platform;
            this.pubkey = pubkey;
            this.value = value;
        }
    }

    String computeCachedEventId(String pubkey) {
        if (!encodingCacheAllowed) return NostrEvent.computeEventIdUncached(pubkey, this);
        NGEPlatform platform = NGEUtils.getPlatform();
        CachedEncoding cached = eventIdCache;
        if (cached != null && cached.platform == platform && Objects.equals(cached.pubkey, pubkey)) return cached.value;
        String id = NostrEvent.computeEventIdUncached(pubkey, this);
        if (id != null) eventIdCache = new CachedEncoding(platform, pubkey, id);
        return id;
    }

    /**
     * Returns the event JSON object, without the relay message envelope.
     * The serialized string is cached on immutable event models for repeated use
     * and remains retained for the lifetime of the event.
     */
    public String toEventJSON() {
        NGEPlatform platform = NGEUtils.getPlatform();
        // Subclasses may override getters or toMap with mutable behavior.
        if (!encodingCacheAllowed || (getClass() != SignedNostrEvent.class && getClass() != ReceivedSignedNostrEvent.class)) {
            return platform.toJSON(toMap());
        }
        CachedEncoding cached = eventJsonCache;
        if (cached != null && cached.platform == platform) return cached.value;
        String json = platform.toJSON(toMap());
        eventJsonCache = new CachedEncoding(platform, null, json);
        return json;
    }

    private static final class TagLookup {

        final String key;
        final List<TagValue> values;
        final TagValue first;

        TagLookup(String key, List<TagValue> values) {
            this.key = key;
            this.values = values;
            this.first = values.get(0);
        }
    }

    public SignedNostrEvent(
        String id,
        NostrPublicKey pubkey,
        int kind,
        String content,
        Instant created_at,
        String signature,
        List<List<String>> tags
    ) {
        this.kind = kind;
        this.content = content;
        this.signature = signature;
        this.pubkey = pubkey.asHex();
        this.parsedPublicKey = pubkey;
        this.identifier = new Identifier(id, created_at);

        ArrayList<List<String>> tagRows = new ArrayList<>(tags.size());
        boolean immutableValues = true;

        for (List<String> tag : tags) {
            if (tag.isEmpty()) continue;
            List<String> row = Collections.unmodifiableList(new ArrayList<>(tag));
            for (Object value : row) {
                if (value != null && !(value instanceof String)) immutableValues = false;
            }
            tagRows.add(row);
        }
        this.tagRows = Collections.unmodifiableList(tagRows);
        this.encodingCacheAllowed = immutableValues;
    }

    /** Parses an event object into an owned immutable model; call verify to authenticate it. */
    public static SignedNostrEvent fromJSON(String json) {
        return new SignedNostrEvent(NGEUtils.getPlatform().parseJsonObject(json));
    }

    private SignedNostrEvent(org.ngengine.platform.JsonObject source) {
        this.encodingCacheAllowed = true;
        this.kind = source.getInt("kind");
        this.content = source.getString("content");
        this.signature = source.getString("sig");
        this.pubkey = source.getString("pubkey");
        this.identifier = new Identifier(source.getString("id"), source.getSecondsInstant("created_at"));
        this.tagRows = source.getStringRows("tags");
    }

    public SignedNostrEvent(Map<String, Object> map) {
        this.encodingCacheAllowed = true;
        this.kind = NGEUtils.safeInt(map.get("kind"));
        this.content = NGEUtils.safeString(map.get("content"));
        this.signature = NGEUtils.safeString(map.get("sig"));
        this.pubkey = NGEUtils.safeString(map.get("pubkey"));

        String id = NGEUtils.safeString(map.get("id"));
        Instant createdAt = NGEUtils.safeSecondsInstant(map.get("created_at"));
        this.identifier = new Identifier(id, createdAt);

        Collection<String[]> tags = NGEUtils.safeCollectionOfStringArray(
            map.getOrDefault("tags", new ArrayList<Collection<String>>())
        );

        ArrayList<List<String>> tagRows = new ArrayList<>(tags.size());

        for (String tag[] : tags) {
            if (tag.length == 0) continue;
            // safeCollectionOfStringArray already owns and validates this array.
            // The immutable row and index can share it without another copy.
            List<String> row = Collections.unmodifiableList(Arrays.asList(tag));
            tagRows.add(row);
        }
        this.tagRows = Collections.unmodifiableList(tagRows);
    }

    private Map<String, List<TagValue>> getTagsIndex() {
        Map<String, List<TagValue>> index = this.tags;
        if (index != null) return index;
        synchronized (this) {
            if (this.tags != null) return this.tags;
            Map<String, List<TagValue>> tagsMap = new LinkedHashMap<>();
            for (List<String> row : tagRows) {
                TagValue value = new TagValue(row, 1);
                tagsMap.computeIfAbsent(row.get(0), key -> new ArrayList<>()).add(value);
            }
            for (Entry<String, List<TagValue>> entry : tagsMap.entrySet()) {
                entry.setValue(Collections.unmodifiableList(entry.getValue()));
            }
            // The owned index is never mutated after publication. Keep it
            // private and protect the exposed views separately.
            this.tags = tagsMap;
            return tagsMap;
        }
    }

    @Override
    public Instant getCreatedAt() {
        return this.identifier.createdAtInstant;
    }

    @Override
    public int getKind() {
        return this.kind;
    }

    @Override
    public String getContent() {
        return this.content;
    }

    public String getSignature() {
        return this.signature;
    }

    public String getId() {
        return this.identifier.id;
    }

    public NostrPublicKey getPubkey() {
        if (parsedPublicKey == null) {
            parsedPublicKey = NostrPublicKey.fromHex(pubkey, false);
        }
        return parsedPublicKey;
    }

    /**
     * @deprecated use getPubkey instead
     */
    @Deprecated
    public NostrPublicKey getAuthor() {
        return this.getPubkey();
    }

    private transient volatile Map<String, Object> cachedFragment;

    @Override
    public Map<String, Object> toMap() {
        if (this.cachedFragment != null) return this.cachedFragment;
        Map<String, Object> cachedFragment = new HashMap<String, Object>();
        cachedFragment.put("id", this.identifier.id);
        cachedFragment.put("pubkey", this.pubkey);
        cachedFragment.put("kind", this.kind);
        cachedFragment.put("content", this.content);
        cachedFragment.put("created_at", this.identifier.createdAt);
        cachedFragment.put("sig", this.signature);
        cachedFragment.put("tags", this.getTagRows());
        this.cachedFragment = ImmutableSnapshot.snapshotMap(cachedFragment, false);
        return this.cachedFragment;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof SignedNostrEvent)) return false;
        if (obj == this) return true;

        SignedNostrEvent e = (SignedNostrEvent) obj;
        return e.identifier.id.equals(identifier.id);
    }

    @Override
    public int hashCode() {
        return identifier.id.hashCode();
    }

    @Override
    public SignedNostrEvent clone() {
        try {
            return (SignedNostrEvent) super.clone();
        } catch (Exception e) {
            throw new RuntimeException("Clone not supported", e);
        }
    }

    public boolean verify() throws Exception {
        if (this.verificationCached.get()) {
            return this.verificationResult.get();
        }
        String computedId = NostrEvent.computeEventId(this.pubkey, this);
        boolean result =
            this.identifier.id.equals(computedId) &&
            NGEUtils.getPlatform().schnorrVerify(computedId, this.signature, this.getPubkey().asReadOnlyBuffer());
        this.verificationResult.set(result);
        this.verificationCached.set(true);
        return result;
    }

    public AsyncTask<Boolean> verifyAsync() {
        if (this.verificationCached.get()) {
            return AsyncTask.completed(this.verificationResult.get());
        }
        String computedId = NostrEvent.computeEventId(this.pubkey, this);
        if (!this.identifier.id.equals(computedId)) {
            this.verificationResult.set(false);
            this.verificationCached.set(true);
            return AsyncTask.completed(false);
        }
        return NGEUtils
            .getPlatform()
            .schnorrVerifyAsync(computedId, this.signature, this.getPubkey().asReadOnlyBuffer())
            .then(result -> {
                this.verificationResult.set(result);
                this.verificationCached.set(true);
                return result;
            });
    }

    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        this.verificationCached = new SafeFlag(false);
        this.verificationResult = new SafeFlag(false);
    }

    public String getIdBech32() {
        try {
            if (bech32Id != null) return bech32Id;
            String id = getId();
            if (id == null) return null;
            ByteBuffer data = NGEUtils.hexToBytes(id);
            bech32Id = Bech32.bech32Encode(BECH32_PREVIX, data);
            assert data.position() == 0 : "Data position must be 0";
            return bech32Id;
        } catch (Bech32Exception e) {
            return null;
        }
    }

    public Identifier getIdentifier() {
        return identifier;
    }

    @Override
    protected String getPrefix() {
        return "EVENT";
    }

    private final transient Collection<Object> thisFragment = Arrays.asList(this);

    @Override
    protected Collection<Object> getFragments() {
        return thisFragment;
    }

    public static class ReceivedSignedNostrEvent extends SignedNostrEvent {

        protected final String subId;

        public ReceivedSignedNostrEvent(String subId, Map<String, Object> map) {
            super(map);
            this.subId = subId;
        }

        public String getSubId() {
            return subId;
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    public static ReceivedSignedNostrEvent parse(List<Object> doc) {
        String prefix = NGEUtils.safeString(doc.get(0));
        if (!prefix.equals("EVENT") || doc.size() < 3) {
            return null;
        }
        String subId = NGEUtils.safeString(doc.get(1));
        Map<String, Object> eventMap = (Map<String, Object>) doc.get(2);
        ReceivedSignedNostrEvent e = new ReceivedSignedNostrEvent(subId, eventMap);
        return e;
    }

    // nip40 expiration: override with cache
    @Override
    public Instant getExpiration() {
        if (expiresAt == null) expiresAt = NostrEvent.super.getExpiration();
        return expiresAt;
    }

    @Override
    public boolean hasTag(String tag) {
        if (tag == null) return false;
        return getTagValues(tag) != null;
    }

    @Override
    public List<TagValue> getTag(String key) {
        return getTagValues(key);
    }

    private List<TagValue> getTagValues(String key) {
        TagLookup cached = firstTagLookup;
        if (cached != null && Objects.equals(cached.key, key)) return cached.values;
        return getIndexedTagValues(key, cached);
    }

    private List<TagValue> getIndexedTagValues(String key, TagLookup cached) {
        List<TagValue> values = getTagsIndex().get(key);
        if (values != null && values.isEmpty()) {
            return null;
        }
        // Cache one successful lookup, usually the tag queried by a filter.
        // Immutable event rows make the result stable. Other queries retain
        // the indexed path without allocating cache entries on every miss.
        if (cached == null && values != null) firstTagLookup = new TagLookup(key, values);
        return values;
    }

    @Override
    public TagValue getFirstTag(String key) {
        TagLookup cached = firstTagLookup;
        if (cached != null && Objects.equals(cached.key, key)) return cached.first;
        // The first-key cache was already checked. Mixed lookups should go
        // directly to the index without repeating the same string comparison.
        List<TagValue> values = getIndexedTagValues(key, cached);
        if (values == null) {
            return null;
        }
        return values.get(0);
    }

    @Override
    public Set<String> listTagKeys() {
        return Collections.unmodifiableSet(getTagsIndex().keySet());
    }

    @Override
    public List<List<String>> getTagRows() {
        return tagRows;
    }

    /**
     * Get coordinates to this event.
     * <p>
     * If the event is addressable or replaceable, it returns a coordinates object with type "a".
     * If the event is not addressable or replaceable, it returns a coordinates object with type "e".
     *
     * Type matches the tag key used to refer to this event as detailed in NIP-01
     * </p>
     *
     * @return
     */
    public NostrEvent.Coordinates getCoordinates() {
        String kind = String.valueOf(getKind());
        if (isAddressable() || isReplaceable()) {
            String pub = getPubkey().asHex();
            TagValue d = getFirstTag("d");
            String coords = kind + ":" + pub + ":" + (d != null ? d.get(0) : "");
            return new NostrEvent.Coordinates("a", String.valueOf(getKind()), coords);
        } else {
            String id = getId();
            return new NostrEvent.Coordinates("e", kind, id);
        }
    }

    public int getPow() {
        String id = getId();
        return ZeroCounter.countLeadingZeroes(id);
    }

    public boolean checkPow(int difficulty) {
        int getMinedDifficulty = getPow();
        return getMinedDifficulty >= difficulty;
    }
}
