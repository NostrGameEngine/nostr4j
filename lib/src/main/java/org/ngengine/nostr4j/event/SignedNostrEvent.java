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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.nostr4j.utils.NostrUtils;

public class SignedNostrEvent extends NostrMessage implements NostrEvent {

    public static class Identifier {

        public final String id;
        public final long createdAt;

        Identifier(String id, long createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }
    }

    private final int kind;
    private final String content;
    private final Map<String, String[]> tags = new HashMap<String, String[]>();
    private final String signature;
    private final String pubkey;
    private final Identifier identifier;
    private String bech32Id;

    private transient NostrPublicKey parsedPublicKey;
    private transient Collection<String[]> taglist;

    public SignedNostrEvent(
        String id,
        String pubkey,
        int kind,
        String content,
        long created_at,
        String signature,
        Collection<String[]> tags
    ) {
        this.kind = kind;
        this.content = content;
        this.signature = signature;
        this.pubkey = pubkey;
        this.identifier = new Identifier(id, created_at);

        for (String[] tag : tags) {
            this.tags.put(tag[0], tag);
        }
    }

    public SignedNostrEvent(
        String id,
        NostrPublicKey pubkey,
        int kind,
        String content,
        long created_at,
        String signature,
        Collection<String[]> tags
    ) {
        this(id, pubkey.asHex(), kind, content, created_at, signature, tags);
        this.parsedPublicKey = pubkey;
    }

    public SignedNostrEvent(Map<String, Object> map) throws Exception {
        this.kind = NostrUtils.safeInt(map.get("kind"));
        this.content = NostrUtils.safeString(map.get("content"));
        this.signature = NostrUtils.safeString(map.get("sig"));
        this.pubkey = NostrUtils.safeString(map.get("pubkey"));

        String id = NostrUtils.safeString(map.get("id"));
        long createdAt = NostrUtils.safeLong(map.get("created_at"));
        this.identifier = new Identifier(id, createdAt);

        Collection<String[]> tags = NostrUtils.safeCollectionOfStringArray(
            map.getOrDefault("tags", new ArrayList<Collection<String>>())
        );
        for (String tag[] : tags) {
            if (tag.length == 0) continue;
            this.tags.put(tag[0], tag);
        }
    }

    @Override
    public long getCreatedAt() {
        return this.identifier.createdAt;
    }

    @Override
    public int getKind() {
        return this.kind;
    }

    @Override
    public String getContent() {
        return this.content;
    }

    @Override
    public Collection<String[]> listTags() {
        if (taglist == null) {
            taglist = Collections.unmodifiableCollection(tags.values());
        }
        return taglist;
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
        cachedFragment.put("tags", this.listTags());
        return cachedFragment;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof SignedNostrEvent)) return false;
        if (obj == this) return true;

        SignedNostrEvent e = (SignedNostrEvent) obj;
        return e.identifier.id == identifier.id;
    }

    @Override
    public SignedNostrEvent clone() {
        return new SignedNostrEvent(
            identifier.id,
            pubkey,
            kind,
            content,
            identifier.createdAt,
            signature,
            listTags()
        );
    }

    @Override
    public String[] getTag(String key) {
        return tags.get(key);
    }

    public boolean verify() throws Exception {
        return NostrUtils
            .getPlatform()
            .verify(this.identifier.id, this.signature, this.getPubkey());
    }

    public String getIdBech32() {
        try {
            if (bech32Id != null) return bech32Id;
            String id = getId();
            if (id == null) return null;
            ByteBuffer data = NostrUtils.hexToBytes(id);
            return Bech32.bech32Encode(BECH32_PREVIX, data);
        } catch (Exception e) {
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

    private final transient Collection<Object> thisFragment = Arrays.asList(
        this
    );

    @Override
    protected Collection<Object> getFragments() {
        return thisFragment;
    }
}
