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

import java.io.*;
import java.time.Instant;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.nip24.Nip24Metadata;
import org.ngengine.nostr4j.utils.NostrUtils;

public class TestNip24Metadata {

    // Dummy implementation of NostrEvent for testing purposes.
    private static class DummyNostrEvent implements NostrEvent, Serializable {

        private final String content;
        private final Instant createdAt;

        public DummyNostrEvent(String content) {
            this.content = content;
            this.createdAt = Instant.now();
        }

        @Override
        public String getContent() {
            return content;
        }

        @Override
        public Instant getCreatedAt() {
            return createdAt;
        }

        @Override
        public int getKind() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getKind'");
        }

        @Override
        public Map<String, List<String>> getTags() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getTags'");
        }

        @Override
        public List<String> getTagValues(String key) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getTagValues'");
        }

        @Override
        public Collection<List<String>> getTagRows() {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'getTagRows'");
        }

        // Other methods can be stubbed or throw UnsupportedOperationException

        @Override
        public boolean hasTag(String tag) {
            // TODO Auto-generated method stub
            throw new UnsupportedOperationException("Unimplemented method 'hasTag'");
        }
    }

    @Test
    public void testMetadataGettersAndSetters() throws Exception {
        String json =
            "{" +
            "\"display_name\": \"Test Display\"," +
            "\"name\": \"TestName\"," +
            "\"about\": \"This is a test about.\"," +
            "\"picture\": \"http://example.com/pic.png\"," +
            "\"website\": \"http://example.com\"," +
            "\"banner\": \"http://example.com/banner.png\"," +
            "\"bot\": true," +
            "\"birthday\": {\"year\":1990, \"month\":5, \"day\":10}" +
            "}";
        DummyNostrEvent event = new DummyNostrEvent(json);
        Nip24Metadata metadata = new Nip24Metadata(event);

        Assert.assertEquals("Test Display", metadata.getDisplayName());
        Assert.assertEquals("TestName", metadata.getName());
        Assert.assertEquals("This is a test about.", metadata.getAbout());
        Assert.assertEquals("http://example.com/pic.png", metadata.getPicture());
        Assert.assertEquals("http://example.com", metadata.getWebsite());
        Assert.assertEquals("http://example.com/banner.png", metadata.getBanner());
        Assert.assertTrue(metadata.isBot());
        Assert.assertNotNull(metadata.getBirthday());

        // Test setters
        metadata.setDisplayName("New Display");
        Assert.assertEquals("New Display", metadata.getDisplayName());

        metadata.setName("NewName");
        Assert.assertEquals("NewName", metadata.getName());

        metadata.setAbout("New about");
        Assert.assertEquals("New about", metadata.getAbout());

        // Change birthday via setBirthday(int, int, int) and verify via Calendar
        metadata.setBirthday(2000, 1, 15);
        Calendar cal = Calendar.getInstance();
        cal.setTime(metadata.getBirthday());
        Assert.assertEquals(2000, cal.get(Calendar.YEAR));
        Assert.assertEquals(0, cal.get(Calendar.MONTH)); // January is 0-based
        Assert.assertEquals(15, cal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void testUpdateEvent() throws Exception {
        String json = "{\"display_name\": \"Test Display\", \"name\": \"TestName\"}";
        DummyNostrEvent event = new DummyNostrEvent(json);
        Nip24Metadata metadata = new Nip24Metadata(event);
        UnsignedNostrEvent updateEvent = metadata.toUpdateEvent();

        // The update event content should be the JSON representation of the metadata
        // map.
        String updateContent = updateEvent.getContent();
        Map parsed = NostrUtils.getPlatform().fromJSON(updateContent, Map.class);
        Assert.assertEquals("Test Display", parsed.get("display_name"));
        Assert.assertEquals("TestName", parsed.get("name"));
    }

    @Test
    public void testSerialization() throws Exception {
        String json = "{\"display_name\": \"SerializeTest\", \"name\": \"SerializeName\"}";
        DummyNostrEvent event = new DummyNostrEvent(json);
        Nip24Metadata original = new Nip24Metadata(event);

        // Serialize the metadata object to a byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }

        // Deserialize from the byte array
        Nip24Metadata deserialized;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            deserialized = (Nip24Metadata) ois.readObject();
        }

        // Verify that selected fields remain equal
        Assert.assertEquals(original.getDisplayName(), deserialized.getDisplayName());
        Assert.assertEquals(original.getName(), deserialized.getName());
        Assert.assertEquals(original.getAbout(), deserialized.getAbout());
        Assert.assertEquals(original.getPicture(), deserialized.getPicture());
        Assert.assertEquals(original.getWebsite(), deserialized.getWebsite());
        Assert.assertEquals(original.getBanner(), deserialized.getBanner());
        Assert.assertEquals(original.isBot(), deserialized.isBot());

        // Optionally verify birthday if set
        if (original.getBirthday() != null && deserialized.getBirthday() != null) {
            Assert.assertEquals(original.getBirthday(), deserialized.getBirthday());
        }
    }
}
