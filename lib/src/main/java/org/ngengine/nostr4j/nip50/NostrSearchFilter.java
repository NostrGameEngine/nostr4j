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
package org.ngengine.nostr4j.nip50;

import java.time.Instant;
import java.util.Map;
import org.ngengine.nostr4j.NostrFilter;

public class NostrSearchFilter extends NostrFilter {

    private String search;

    public NostrSearchFilter search(String search) {
        this.search = search;
        return this;
    }

    public NostrSearchFilter() {}

    protected Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        if (search != null && !search.isEmpty()) {
            map.put("search", search);
        }
        return map;
    }

    public NostrSearchFilter(Map<String, Object> map) throws Exception {
        super(map);
        if (map.containsKey("search")) {
            String search = (String) map.get("search");
            if (!search.isEmpty()) {
                this.search = search;
            }
        }
    }

    public NostrSearchFilter id(String id) {
        return (NostrSearchFilter) super.id(id);
    }

    public NostrSearchFilter author(String author) {
        return (NostrSearchFilter) super.author(author);
    }

    public NostrSearchFilter kind(int kind) {
        return (NostrSearchFilter) super.kind(kind);
    }

    public NostrSearchFilter since(Instant since) {
        return (NostrSearchFilter) super.since(since);
    }

    public NostrSearchFilter until(Instant until) {
        return (NostrSearchFilter) super.until(until);
    }

    public NostrSearchFilter limit(int limit) {
        return (NostrSearchFilter) super.limit(limit);
    }

    public NostrSearchFilter tag(String key, String... values) {
        return (NostrSearchFilter) super.tag(key, values);
    }
}
