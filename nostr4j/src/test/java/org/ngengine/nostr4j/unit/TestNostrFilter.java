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
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class TestNostrFilter {

    public class TestableNostrFilter extends NostrFilter {

        public TestableNostrFilter() {
            super();
        }

        public TestableNostrFilter(Map<String, Object> map) throws Exception {
            super(map);
        }

        @Override
        public Map<String, Object> toMap() {
            return super.toMap();
        }
    }

    void assertVerify(NostrFilter filter) {
        assertEquals(filter.getAuthors().get(0), "2dfd5a7b5389aa6b550efbf996ef5cd2708fbce28de0bc48d2d5c0b2253f9652");
        assertEquals(filter.getAuthors().get(1), "77cca49d43c6013b25be4991e5be283be83d156c6fe30d0e6fa89306d47c5253");

        assertEquals(filter.getKinds().get(0).intValue(), 0);
        assertEquals(filter.getKinds().get(1).intValue(), 1);

        assertEquals(filter.getTags().get("a").get(0), "1");

        assertEquals(filter.getTags().get("b").get(0), "1");
        assertEquals(filter.getTags().get("b").get(1), "2");
        assertEquals(filter.getTags().get("b").get(2), "3");

        List<String> values = filter.getTagValues("b");
        assertEquals(values.get(0), "1");
        assertEquals(values.get(1), "2");
        assertEquals(values.get(2), "3");

        assertEquals(filter.getLimit().intValue(), 10);
        assertEquals(filter.getUntil().toEpochMilli(), 1000);
        assertEquals(filter.getSince().toEpochMilli(), 2000);

        assertEquals(filter.getIds().get(0), "123");
        assertEquals(filter.getIds().get(1), "234");
    }

    @SuppressWarnings("unchecked")
    private void assertJsonRepresentsMap(String json, Map<String, Object> expected, NGEPlatform platform) throws Exception {
        NostrFilter fromJson = new TestableNostrFilter((Map<String, Object>) platform.fromJSON(json, Map.class));
        Map<String, Object> normalized = ((TestableNostrFilter) fromJson).toMap();
        assertEquals(expected, normalized);
    }

    @Test
    public void testFilterSerialization() throws Exception {
        NostrFilter filter = new TestableNostrFilter()
            .withAuthor(NostrPublicKey.fromHex("2dfd5a7b5389aa6b550efbf996ef5cd2708fbce28de0bc48d2d5c0b2253f9652"))
            .withAuthor(NostrPublicKey.fromHex("77cca49d43c6013b25be4991e5be283be83d156c6fe30d0e6fa89306d47c5253"))
            .withKind(0)
            .withKind(1)
            .withTag("a", "1")
            .withTag("b", "1", "2", "3")
            .limit(10)
            .until(Instant.ofEpochMilli(1000))
            .since(Instant.ofEpochMilli(2000))
            .withId("123")
            .withId("234");

        assertVerify(filter);

        Map<String, Object> map = ((TestableNostrFilter) filter).toMap();
        NGEPlatform p = NGEUtils.getPlatform();
        String json = p.toJSON(map);
        assertJsonRepresentsMap(json, map, p);

        NostrFilter loadFromMap = new TestableNostrFilter(map);
        assertVerify(loadFromMap);

        NostrFilter loadFromJson = new TestableNostrFilter(p.fromJSON(json, Map.class));
        assertVerify(loadFromJson);

        String json2 = p.toJSON(((TestableNostrFilter) loadFromMap).toMap());
        assertJsonRepresentsMap(json2, map, p);

        String json3 = p.toJSON(((TestableNostrFilter) loadFromJson).toMap());
        assertJsonRepresentsMap(json3, map, p);
    }

    @Test
    public void testFilterCloneDeepCopiesTagLists() throws Exception {
        NostrFilter original = new NostrFilter().withTag("p", "alpha", "beta");
        NostrFilter clone = original.clone();

        List<String> cloneTagValues = clone.getTagValues("p");
        cloneTagValues.set(0, "changed");
        clone.getTags().put("x", new java.util.ArrayList<>(List.of("1")));

        assertEquals("alpha", original.getTagValues("p").get(0));
        assertEquals("changed", clone.getTagValues("p").get(0));
        assertFalse(original.getTags().containsKey("x"));
        assertEquals(List.of("alpha", "beta"), original.getTagValues("p"));
    }
}
