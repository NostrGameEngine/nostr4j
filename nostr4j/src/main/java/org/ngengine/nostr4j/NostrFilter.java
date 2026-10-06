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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ngengine.nostr4j.event.NostrEvent.TagValue;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.proto.NostrMessageFragment;
import org.ngengine.platform.NGEUtils;

public class NostrFilter extends NostrMessageFragment implements Cloneable {

    private List<String> ids;
    private List<String> authors;
    private List<Integer> kinds;
    private Instant since;
    private Instant until;
    private Integer limit;
    private Map<String, List<String>> tags;

    public NostrFilter() {}

    public NostrFilter(Map<String, Object> map) {
        if (map.containsKey("ids")) {
            ids = NGEUtils.safeStringList(map.get("ids"));
        }
        if (map.containsKey("authors")) {
            authors = NGEUtils.safeStringList(map.get("authors"));
        }
        if (map.containsKey("kinds")) {
            kinds = NGEUtils.safeIntList(map.get("kinds"));
        }
        if (map.containsKey("since")) {
            since = Instant.ofEpochSecond(NGEUtils.safeLong(map.get("since")));
        }
        if (map.containsKey("until")) {
            until = Instant.ofEpochSecond(NGEUtils.safeLong(map.get("until")));
        }
        if (map.containsKey("limit")) {
            limit = NGEUtils.safeInt(map.get("limit"));
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("#")) {
                String tagKey = key.substring(1);
                List<String> value = NGEUtils.safeStringList(entry.getValue());
                if (tags == null) tags = new HashMap<>();
                tags.put(tagKey, value);
            }
        }
    }

    @Override
    public NostrFilter clone() {
        try {
            NostrFilter cl = (NostrFilter) super.clone();
            cl.ids = ids != null ? new ArrayList<>(ids) : null;
            cl.authors = authors != null ? new ArrayList<>(authors) : null;
            cl.kinds = kinds != null ? new ArrayList<>(kinds) : null;
            if (tags != null) {
                cl.tags = new HashMap<>();
                for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
                    cl.tags.put(entry.getKey(), entry.getValue() != null ? new ArrayList<>(entry.getValue()) : null);
                }
            } else {
                cl.tags = null;
            }
            return cl;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone not supported for NostrFilter", e);
        }
    }

    public NostrFilter withId(String id) {
        if (ids == null) ids = new ArrayList<>();
        ids.add(id);
        return this;
    }

    public List<String> getIds() {
        return ids;
    }

    public NostrFilter withAuthor(String author) {
        if (authors == null) authors = new ArrayList<>();
        authors.add(author);
        return this;
    }

    public NostrFilter withAuthor(NostrPublicKey author) {
        if (authors == null) authors = new ArrayList<>();
        authors.add(author.asHex());
        return this;
    }

    public List<String> getAuthors() {
        return authors;
    }

    public NostrFilter withKind(int kind) {
        if (kinds == null) kinds = new ArrayList<>();
        kinds.add(kind);
        return this;
    }

    public List<Integer> getKinds() {
        return kinds;
    }

    public NostrFilter since(Instant since) {
        this.since = since;
        return this;
    }

    public Instant getSince() {
        return since;
    }

    public Instant getUntil() {
        return until;
    }

    public NostrFilter until(Instant until) {
        this.until = until;
        return this;
    }

    public NostrFilter limit(int limit) {
        this.limit = limit;
        return this;
    }

    public Integer getLimit() {
        return limit;
    }

    public NostrFilter withTag(String key, String... values) {
        if (tags == null) tags = new java.util.HashMap<>();
        tags.put(key, Arrays.asList(values));
        return this;
    }

    public Map<String, List<String>> getTags() {
        return tags;
    }

    public List<String> getTagValues(String key) {
        if (tags == null) return null;
        return tags.get(key);
    }

    @Override
    protected Map<String, Object> toMap() {
        Map<String, Object> serial = new HashMap<>();
        if (ids != null) serial.put("ids", ids);
        if (authors != null) serial.put("authors", authors);
        if (kinds != null) serial.put("kinds", kinds);
        if (since != null) serial.put("since", since.getEpochSecond());
        if (until != null) serial.put("until", until.getEpochSecond());
        if (limit != null) serial.put("limit", limit);
        if (tags != null) {
            for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
                String key = entry.getKey();
                List<String> value = entry.getValue();
                serial.put("#" + key, value);
            }
        }
        return serial;
    }

    /**
     * Checks if the given event matches this filter according to NIP-01 specs
     * @param event the event to check
     * @return true if the event matches the filter, false otherwise
     */
    public boolean matches(SignedNostrEvent event) {
        return matches(event, 0, false);
    }

    /**
     * Checks if the given event matches this filter
     * @param event the event to check
     * @param anyTagValue if true, the filter will match if any of the tag values matches, otherwise it will only match if the first tag value matches (default nostr behavior per nip-01 spec).
     * @return
     */
    public boolean matches(SignedNostrEvent event, boolean anyTagValue) {
        return matches(event, 0, anyTagValue);
    }

    /**
     * Checks if the given event matches this filter according to NIP-01 specs
     * @param event the event to check
     * @param count the number of events already matched by this filter (used for limit)
     * @return true if the event matches the filter, false otherwise
     */
    public boolean matches(SignedNostrEvent event, int count) {
        return matches(event, count, false);
    }

    /**
     * Checks if the given event matches this filter
     * @param event the event to check
     * @param count the number of events already matched by this filter (used for limit)
     * @param anyTagValue if true, the filter will match if any of the tag values matches, otherwise it will only match if the first tag value matches (default nostr behavior per nip-01 spec).
     * @return true if the event matches the filter, false otherwise
     */
    public boolean matches(SignedNostrEvent event, int count, boolean anyTagValue) {
        if (!matchesFields(event, count)) return false;
        if (tags != null) {
            for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
                if (!matchesTag(event, entry.getKey(), entry.getValue(), anyTagValue)) return false;
            }
        }
        return true;
    }

    /**
     * Captures this filter for repeated matches without allocating map iterators per event.
     * Like {@link #matches(SignedNostrEvent)}, each call uses count zero; the predicate
     * does not maintain a cumulative match count for the limit.
     */
    public java.util.function.Predicate<SignedNostrEvent> prepare() {
        return prepare(false);
    }

    /**
     * Captures the filter's current lists and tag rules; subsequent filter edits do not affect it.
     * Each call uses count zero. Use {@link #matches(SignedNostrEvent, int, boolean)}
     * when applying a limit to a running count of matched events.
     */
    public java.util.function.Predicate<SignedNostrEvent> prepare(boolean anyTagValue) {
        NostrFilter snapshot = clone();
        if (getClass() != NostrFilter.class) {
            return anyTagValue ? event -> snapshot.matches(event, true) : snapshot::matches;
        }
        List<Map.Entry<String, List<String>>> rules = snapshot.tags == null
            ? java.util.Collections.emptyList()
            : new ArrayList<>(snapshot.tags.entrySet());
        return event -> {
            if (!snapshot.matchesFields(event, 0)) return false;
            for (int i = 0; i < rules.size(); i++) {
                Map.Entry<String, List<String>> rule = rules.get(i);
                if (!matchesTag(event, rule.getKey(), rule.getValue(), anyTagValue)) return false;
            }
            return true;
        };
    }

    private boolean matchesFields(SignedNostrEvent event, int count) {
        if (limit != null && count >= limit) {
            return false;
        }
        if (ids != null && !ids.contains(event.getId())) {
            return false;
        }
        if (authors != null && !authors.contains(event.getPubkey().asHex())) {
            return false;
        }
        if (kinds != null) {
            int kind = event.getKind();
            if (kinds.size() == 1) {
                Object expected = kinds.get(0);
                if (!(expected instanceof Integer) || ((Integer) expected).intValue() != kind) return false;
            } else if (!kinds.contains(kind)) {
                return false;
            }
        }
        if (since != null && event.getCreatedAt().isBefore(since)) {
            return false;
        }
        if (until != null && event.getCreatedAt().isAfter(until)) {
            return false;
        }
        return true;
    }

    private static boolean matchesTag(SignedNostrEvent event, String key, List<String> values, boolean anyTagValue) {
        if (!event.hasTag(key)) return false;
        List<TagValue> eventTags = event.getTag(key);
        if (eventTags == null) return false;
        if (!anyTagValue && values.size() == 1 && eventTags.size() == 1) {
            return Objects.equals(eventTags.get(0).get(0), values.get(0));
        }
        for (String expected : values) {
            for (TagValue tag : eventTags) {
                if (anyTagValue) {
                    for (String value : tag.getAll()) {
                        if (Objects.equals(value, expected)) return true;
                    }
                } else if (Objects.equals(tag.get(0), expected)) {
                    return true;
                }
            }
        }
        return false;
    }
}
