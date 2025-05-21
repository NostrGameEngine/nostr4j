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
import java.util.Set;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.nostr4j.utils.Bech32.Bech32Exception;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class SignedNostrEvent extends NostrMessage implements NostrEvent {

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
    private Map<String, List<TagValue>> tags;
    private final List<List<String>> tagRows;
    private final String signature;
    private final String pubkey;
    private final Identifier identifier;

    private transient String bech32Id;
    private transient NostrPublicKey parsedPublicKey;
    private transient Instant expiresAt;

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
        this.identifier = new Identifier(id, created_at);

        Map<String, List<TagValue>> tagsMap = new LinkedHashMap<>();

        for (List<String> tag : tags) {
            ArrayList<String> values = new ArrayList<>();
            for (int i = 1; i < tag.size(); i++) {
                values.add(tag.get(i));
            }
            TagValue tagValue = new TagValue(values);
            List<TagValue> tagValues = tagsMap.computeIfAbsent(tag.get(0), k -> new ArrayList<>());
            tagValues.add(tagValue);
        }

        // seal all tag entries by making the List<TagValue> immutable
        for (Entry<String, List<TagValue>> entry : tagsMap.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }

        this.tags = Collections.unmodifiableMap(tagsMap);
        this.tagRows = Collections.unmodifiableList(tags);
    }

    public SignedNostrEvent(Map<String, Object> map) {
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

        Map<String, List<TagValue>> tagsMap = new LinkedHashMap<>();
        ArrayList<List<String>> tagRows = new ArrayList<>();

        for (String tag[] : tags) {
            if (tag.length == 0) continue;
            ArrayList<String> values = new ArrayList<>();
            for (int i = 1; i < tag.length; i++) {
                values.add(tag[i]);
            }
            TagValue tagValue = new TagValue(values);
            List<TagValue> tagValues = tagsMap.computeIfAbsent(tag[0], k -> new ArrayList<>());
            tagValues.add(tagValue);
            tagRows.add(Arrays.asList(tag));
        }

        this.tags = Collections.unmodifiableMap(tagsMap);
        this.tagRows = Collections.unmodifiableList(tagRows);
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
            parsedPublicKey = NostrPublicKey.fromHex(pubkey);
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

    private transient Map<String, Object> cachedFragment;

    @Override
    public Map<String, Object> toMap() {
        if (cachedFragment != null) return cachedFragment;
        cachedFragment = new HashMap<String, Object>();
        cachedFragment.put("id", this.identifier.id);
        cachedFragment.put("pubkey", this.pubkey);
        cachedFragment.put("kind", this.kind);
        cachedFragment.put("content", this.content);
        cachedFragment.put("created_at", this.identifier.createdAt);
        cachedFragment.put("sig", this.signature);
        cachedFragment.put("tags", this.getTagRows());
        return cachedFragment;
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
        return NGEUtils.getPlatform().verify(this.identifier.id, this.signature, this.getPubkey()._array());
    }

    public AsyncTask<Boolean> verifyAsync() {
        return NGEUtils.getPlatform().verifyAsync(this.identifier.id, this.signature, this.getPubkey()._array());
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
        if (expiresAt != null) return expiresAt;
        String tag = getFirstTag("expiration").get(0);
        if (tag != null) {
            long expires = NGEUtils.safeLong(tag);
            expiresAt = Instant.ofEpochSecond(expires);
        } else {
            expiresAt = Instant.now().plusSeconds(60 * 60 * 24 * 365 * 2100);
        }
        return expiresAt;
    }

    @Override
    public boolean hasTag(String tag) {
        if (tag == null) return false;
        return tags.get(tag) != null;
    }

    @Override
    public Collection<TagValue> getTag(String key) {
        List<TagValue> values = tags.get(key);
        if (values != null && values.isEmpty()) {
            return null;
        }
        return values;
    }

    @Override
    public TagValue getFirstTag(String key) {
        List<TagValue> values = tags.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    @Override
    public Set<String> listTagKeys() {
        return tags.keySet();
    }

    @Override
    public List<List<String>> getTagRows() {
        return tagRows;
    }
}
