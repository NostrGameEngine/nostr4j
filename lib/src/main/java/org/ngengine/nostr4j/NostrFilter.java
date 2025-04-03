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
package org.ngengine.nostr4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.transport.NostrMessageFragment;

public class NostrFilter extends NostrMessageFragment {

    private Collection<String> ids;
    private Collection<String> authors;
    private Collection<Integer> kinds;
    private Long since;
    private Long until;
    private Integer limit;
    private Map<String, String[]> tags;

    public NostrFilter id(String id) {
        if (ids == null) ids = new ArrayList<>();
        ids.add(id);
        return this;
    }

    public NostrFilter author(String author) {
        if (authors == null) authors = new ArrayList<>();
        authors.add(author);
        return this;
    }

    public NostrFilter kind(int kind) {
        if (kinds == null) kinds = new ArrayList<>();
        kinds.add(kind);
        return this;
    }

    public NostrFilter since(Instant since) {
        this.since = since.getEpochSecond();
        return this;
    }

    public NostrFilter until(Instant until) {
        this.until = until.getEpochSecond();
        return this;
    }

    public NostrFilter limit(int limit) {
        this.limit = limit;
        return this;
    }

    public NostrFilter tag(String key, String... values) {
        if (tags == null) tags = new java.util.HashMap<>();
        tags.put(key, values);
        return this;
    }

    @Override
    protected Map<String, Object> toMap() {
        Map<String, Object> serial = new HashMap<>();
        if (ids != null) serial.put("ids", ids);
        if (authors != null) serial.put("authors", authors);
        if (kinds != null) serial.put("kinds", kinds);
        if (since != null) serial.put("since", since);
        if (until != null) serial.put("until", until);
        if (limit != null) serial.put("limit", limit);
        if (tags != null) {
            for (Map.Entry<String, String[]> entry : tags.entrySet()) {
                String key = entry.getKey();
                String[] value = entry.getValue();
                serial.put("#" + key, value);
            }
        }
        return serial;
    }

    public NostrFilter() {}

    public NostrFilter(Map<String, Object> map) throws Exception {
        if (map.containsKey("ids")) {
            ids = (Collection<String>) map.get("ids");
        }
        if (map.containsKey("authors")) {
            authors = (Collection<String>) map.get("authors");
        }
        if (map.containsKey("kinds")) {
            kinds = (Collection<Integer>) map.get("kinds");
        }
        if (map.containsKey("since")) {
            since = ((Number) map.get("since")).longValue();
        }
        if (map.containsKey("until")) {
            until = ((Number) map.get("until")).longValue();
        }
        if (map.containsKey("limit")) {
            limit = ((Number) map.get("limit")).intValue();
        }
        if (map.containsKey("tags")) {
            Collection<String[]> tags = (Collection<String[]>) map.get("tags");
            this.tags = new HashMap<>();
            for (String[] tag : tags) {
                this.tags.put(tag[0].substring(1), tag);
            }
        }
    }
}
