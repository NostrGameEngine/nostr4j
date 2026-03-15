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
import java.util.Map.Entry;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import org.ngengine.platform.NGEUtils;

public class UnsignedNostrEvent implements NostrEvent {

    private Instant createdAt = Instant.now();
    private int kind = 1;
    private String content = "";

    private Map<String, List<TagValue>> tags = new LinkedHashMap<>();

    private transient List<List<String>> tagRows;

    public UnsignedNostrEvent() {}

    public UnsignedNostrEvent(Map<String, Object> map) {
        fromMap(map);
    }

    public synchronized UnsignedNostrEvent withKind(int kind) {
        this.kind = kind;
        return this;
    }

    public synchronized UnsignedNostrEvent withContent(String content) {
        this.content = NGEUtils.safeString(content);
        return this;
    }

    public synchronized UnsignedNostrEvent createdAt(Instant created_at) {
        this.createdAt = created_at;
        return this;
    }

    // nip40 expiration
    public synchronized UnsignedNostrEvent withExpiration(Instant expiresAt) {
        clearTags("expiration");
        if (expiresAt != null) {
            withTag("expiration", String.valueOf(Objects.requireNonNull(expiresAt).getEpochSecond()));
        }
        return this;
    }

    public synchronized UnsignedNostrEvent withTag(String key, String... values) {
        if (values == null || values.length == 0) {
            return clearTags(key);
        }
        return withTag(key, new TagValue(Arrays.asList(values)));
    }

    public synchronized UnsignedNostrEvent withTag(String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return clearTags(key);
        }
        return withTag(key, new TagValue(Collections.unmodifiableList(new ArrayList<>(values))));
    }

    public synchronized UnsignedNostrEvent withTag(String key, TagValue value) {
        if (value == null || value.size() == 0) {
            return clearTags(key);
        }
        tagRows = null;
        List<TagValue> tagValues = tags.computeIfAbsent(key, k -> new ArrayList<>());
        tagValues.add(new TagValue(Collections.unmodifiableList(new ArrayList<>(value.getAll()))));
        return this;
    }

    public synchronized UnsignedNostrEvent replaceTag(String key, String... values) {
        clearTags(key);
        return withTag(key, values);
    }

    public synchronized UnsignedNostrEvent replaceTag(String key, List<String> values) {
        clearTags(key);
        return withTag(key, values);
    }

    public synchronized UnsignedNostrEvent replaceTag(String key, TagValue value) {
        clearTags(key);
        return withTag(key, value);
    }

    public synchronized UnsignedNostrEvent clearTags(String key) {
        tagRows = null;
        tags.remove(key);
        return this;
    }

    @Override
    public synchronized List<List<String>> getTagRows() {
        if (tagRows == null) {
            ArrayList<List<String>> tagRows = new ArrayList<>();
            for (Entry<String, List<TagValue>> entry : tags.entrySet()) {
                for (TagValue value : entry.getValue()) {
                    if (value == null) continue;
                    ArrayList<String> row = new ArrayList<>();
                    row.add(entry.getKey());
                    for (String v : value.getAll()) {
                        row.add(v);
                    }
                    tagRows.add(Collections.unmodifiableList(row));
                }
            }
            this.tagRows = Collections.unmodifiableList(tagRows);
        }
        return tagRows;
    }

    public synchronized UnsignedNostrEvent fromMap(Map<String, Object> map) {
        this.kind = NGEUtils.safeInt(map.get("kind"));
        this.content = NGEUtils.safeString(map.get("content"));
        this.createdAt = NGEUtils.safeSecondsInstant(map.get("created_at"));
        this.tags.clear();
        this.tagRows = null;
        Collection<String[]> tags = NGEUtils.safeCollectionOfStringArray(map.getOrDefault("tags", new ArrayList<String[]>()));
        for (String[] tag : tags) {
            if (tag.length == 0) continue;
            String key = tag[0];
            String[] values = Arrays.copyOfRange(tag, 1, tag.length);
            if (values.length == 0) {
                clearTags(key);
            } else {
                withTag(key, values);
            }
        }
        return this;
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UnsignedNostrEvent{");
        sb.append("\tcreatedAt=").append(createdAt);
        sb.append(",\n\tkind=").append(kind);
        sb.append(",\n\tcontent='").append(content).append('\'');
        sb.append(",\n\ttags=\n");
        for (List<String> tagRow : getTagRows()) {
            sb.append("\t\t");
            for (String tag : tagRow) {
                sb.append(tag).append(", ");
            }
            sb.append('\n');
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public synchronized Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public synchronized int getKind() {
        return kind;
    }

    @Override
    public synchronized String getContent() {
        return content;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof UnsignedNostrEvent)) return false;
        if (obj == this) return true;
        UnsignedNostrEvent e = (UnsignedNostrEvent) obj;
        if (
            !(e.getCreatedAt().equals(getCreatedAt()) && e.getKind() == getKind() && e.getContent().equals(getContent()))
        ) return false;

        List<List<String>> tagRows1 = getTagRows();
        List<List<String>> tagRows2 = e.getTagRows();
        if (tagRows1.size() != tagRows2.size()) return false;
        for (int i = 0; i < tagRows1.size(); i++) {
            List<String> row1 = tagRows1.get(i);
            List<String> row2 = tagRows2.get(i);
            if (!row1.equals(row2)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public synchronized int hashCode() {
        return toString().hashCode();
    }

    @Override
    public synchronized UnsignedNostrEvent clone() {
        try {
            UnsignedNostrEvent clone = (UnsignedNostrEvent) super.clone();
            LinkedHashMap<String, List<TagValue>> tagsCopy = new LinkedHashMap<>();
            for (Entry<String, List<TagValue>> entry : this.tags.entrySet()) {
                tagsCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            clone.tags = tagsCopy;
            clone.tagRows = null;
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone not supported", e);
        }
    }

    @Override
    public synchronized boolean hasTag(String tag) {
        if (tag == null) return false;
        return tags.get(tag) != null;
    }

    @Override
    public synchronized Collection<TagValue> getTag(String key) {
        List<TagValue> values = tags.get(key);
        if (values != null && values.isEmpty()) {
            return null;
        }
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override
    public synchronized TagValue getFirstTag(String key) {
        List<TagValue> values = tags.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    @Override
    public synchronized Set<String> listTagKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tags.keySet()));
    }
}
