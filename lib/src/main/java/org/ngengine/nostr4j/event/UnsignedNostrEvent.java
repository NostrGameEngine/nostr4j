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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ngengine.nostr4j.utils.NostrUtils;

public class UnsignedNostrEvent implements NostrEvent {

    private Instant createdAt = Instant.now();
    private int kind = 1;
    private String content = "";
    private LinkedHashMap<String, List<String>> tags = new LinkedHashMap<String, List<String>>();

    private transient Map<String, List<String>> tagsRO;
    private transient Collection<List<String>> tagRows;

    public UnsignedNostrEvent withKind(int kind) {
        this.kind = kind;
        return this;
    }

    public UnsignedNostrEvent withContent(String content) {
        this.content = content;

        return this;
    }

    public UnsignedNostrEvent createdAt(Instant created_at) {
        this.createdAt = created_at;
        return this;
    }

    // nip40 expiration
    public UnsignedNostrEvent withExpiration(Instant expiresAt) {
        if (expiresAt != null) {
            withTag("expiration", String.valueOf(Objects.requireNonNull(expiresAt).getEpochSecond()));
        } else {
            withoutTag("expiration");
        }
        return this;
    }

    public UnsignedNostrEvent withTag(String key, String... values) {
        if (values == null || values.length == 0) {
            return withoutTag(key);
        }
        tagRows = null;
        tags.put(key, Collections.unmodifiableList(Arrays.asList(values)));
        return this;
    }

    public UnsignedNostrEvent withoutTag(String key) {
        tagRows = null;
        tags.remove(key);
        return this;
    }

    @Override
    public List<String> getTagValues(String key) {
        return tags.get(key);
    }

    @Override
    public Map<String, List<String>> getTags() {
        if (tagsRO == null) {
            tagsRO = Collections.unmodifiableMap(tags);
        }
        return tagsRO;
    }

    @Override
    public Collection<List<String>> getTagRows() {
        if (tagRows == null) {
            ArrayList<List<String>> tagRows = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
                ArrayList<String> row = new ArrayList<>();
                row.add(entry.getKey());
                List<String> values = entry.getValue();
                if (values != null) {
                    for (String value : values) {
                        row.add(value);
                    }
                }
                tagRows.add(Collections.unmodifiableList(row));
            }
            this.tagRows = Collections.unmodifiableCollection(tagRows);
        }
        return tagRows;
    }

    public UnsignedNostrEvent fromMap(Map<String, Object> map) {
        this.kind = NostrUtils.safeInt(map.get("kind"));
        this.content = NostrUtils.safeString(map.get("content"));
        this.createdAt = NostrUtils.safeSecondsInstant(map.get("created_at"));
        Collection<String[]> tags = NostrUtils.safeCollectionOfStringArray(map.getOrDefault("tags", new ArrayList<String[]>()));
        for (String[] tag : tags) {
            if (tag.length == 0) continue;
            String key = tag[0];
            String[] values = Arrays.copyOfRange(tag, 1, tag.length);
            if (values.length == 0) {
                withoutTag(key);
            } else {
                withTag(key, values);
            }
        }
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UnsignedNostrEvent{");
        sb.append("\tcreatedAt=").append(createdAt);
        sb.append(",\n\tkind=").append(kind);
        sb.append(",\n\tcontent='").append(content).append('\'');
        sb.append(",\n\ttags=\n");
        for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
            sb.append("\t\t").append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public int getKind() {
        return kind;
    }

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof UnsignedNostrEvent)) return false;
        if (obj == this) return true;

        UnsignedNostrEvent e = (UnsignedNostrEvent) obj;
        return (
            e.getCreatedAt().equals(getCreatedAt()) &&
            e.getKind() == getKind() &&
            e.getContent().equals(getContent()) &&
            NostrUtils.equalsWithOrder(e.getTags(), getTags())
        );
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public UnsignedNostrEvent clone() {
        try {
            UnsignedNostrEvent clone = (UnsignedNostrEvent) super.clone();
            clone.tags = new LinkedHashMap<>(this.tags);
            clone.tagsRO = null;
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone not supported", e);
        }
    }

    @Override
    public boolean hasTag(String tag) {
        if (tag == null) return false;
        return tags.get(tag) != null;
    }
}
