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
package org.ngengine.nostr4j.nip01;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.NostrEvent.TagValue;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class Nip01UserMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    public final Map<String, Object> metadata;
    protected final NostrEvent sourceEvent;

    public Nip01UserMetadata(NostrEvent source) {
        if (source.getKind() != 0) {
            throw new IllegalArgumentException("Invalid event kind");
        }
        this.sourceEvent = source;
        NGEPlatform platform = NGEUtils.getPlatform();
        String content = sourceEvent.getContent();
        Map<String, Object> meta = platform.fromJSON(content, Map.class);
        if (meta == null) throw new IllegalArgumentException("Invalid metadata");
        this.metadata = meta;
    }

    public UnsignedNostrEvent toUpdateEvent() {
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(0);
        event.createdAt(Instant.now());
        event.withContent(NGEUtils.getPlatform().toJSON(metadata));
        for (String key : sourceEvent.listTagKeys()) {
            Collection<TagValue> tags = sourceEvent.getTag(key);
            for (TagValue tag : tags) {
                event.withTag(key, tag);
            }
        }
        return event;
    }

    public NostrEvent getSourceEvent() {
        return sourceEvent;
    }

    public String getName() {
        String v = NGEUtils.safeString(metadata.get("name"));
        if (v.isEmpty()) return null;
        return NGEUtils.safeString(v);
    }

    public void setName(String name) {
        metadata.put("name", name);
    }

    public String getAbout() {
        Object v = metadata.get("about");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    public void setAbout(String about) {
        metadata.put("about", about);
    }

    public String getPicture() {
        Object v = metadata.get("picture");
        if (v == null) return null;
        return NGEUtils.safeString(v);
    }

    public void setPicture(String picture) {
        metadata.put("picture", picture);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Nip01UserMetadata)) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        Nip01UserMetadata other = (Nip01UserMetadata) obj;
        return sourceEvent.equals(other.sourceEvent);
    }

    @Override
    public int hashCode() {
        if (sourceEvent == null) return 0;
        return sourceEvent.hashCode();
    }

    @Override
    public String toString() {
        return sourceEvent.toString();
    }
}
