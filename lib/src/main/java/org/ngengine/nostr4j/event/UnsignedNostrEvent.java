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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.utils.NostrUtils;

public class UnsignedNostrEvent implements NostrEvent {

    private Instant createdAt = Instant.now();
    private int kind = 1;
    private String content = "";
    private Map<String, String[]> tags = new HashMap<String, String[]>();

    private transient Collection<String[]> taglist;

    public UnsignedNostrEvent setKind(int kind) {
        this.kind = kind;

        return this;
    }

    public UnsignedNostrEvent setContent(String content) {
        this.content = content;

        return this;
    }

    public UnsignedNostrEvent setCreatedAt(Instant created_at) {
        this.createdAt = created_at;
        return this;
    }

    public void setTag(String... tag) {
        tags.put(tag[0], tag);
    }

    @Override
    public String[] getTag(String key) {
        return tags.get(key);
    }

    public UnsignedNostrEvent setTags(Collection<String[]> tags) {
        this.tags.clear();
        for (String[] tag : tags) {
            this.tags.put(tag[0], tag);
        }

        return this;
    }

    @Override
    public Collection<String[]> listTags() {
        if (taglist == null) {
            taglist = Collections.unmodifiableCollection(tags.values());
        }
        return taglist;
    }

    public UnsignedNostrEvent fromMap(Map<String, Object> map) {
        this.kind = NostrUtils.safeInt(map.get("kind"));
        this.content = map.get("content").toString();
        this.createdAt = NostrUtils.safeSecondsInstant(map.get("created_at"));
        Collection<String[]> tags = NostrUtils.safeCollectionOfStringArray(map.getOrDefault("tags", new ArrayList<String[]>()));
        setTags(tags);
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UnsignedNostrEvent{");
        sb.append("createdAt=").append(createdAt);
        sb.append(", kind=").append(kind);
        sb.append(", content='").append(content).append('\'');
        sb.append(", tags=").append(tags);
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
            e.listTags().equals(listTags())
        );
    }

    @Override
    public UnsignedNostrEvent clone() {
        return new UnsignedNostrEvent().setKind(kind).setContent(content).setTags(listTags()).setCreatedAt(createdAt);
    }
}
