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
package org.ngengine.nostr4j.nip46;

import static org.junit.Assert.*;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

public class TestNostrconnectUrl {

    private NostrPublicKey testPubKey;
    private List<String> testRelays;
    private String testSecret;
    private Nip46AppMetadata testMetadata;

    @Before
    public void setUp() throws Exception {
        // Initialize test objects
        testPubKey = NostrPublicKey.fromHex("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d");
        testRelays = Arrays.asList("wss://relay.example.com", "wss://relay2.example.com");
        testSecret = "mySecretToken123";
        testMetadata =
            new Nip46AppMetadata(
                "Test App",
                "https://testapp.example.com",
                "https://testapp.example.com/logo.png",
                Arrays.asList("sign_event", "get_public_key")
            );
    }

    @Test
    public void testToString() {
        // Create a NostrconnectUrl object
        NostrconnectUrl url = new NostrconnectUrl(testPubKey, testRelays, testSecret, testMetadata);

        // Convert to string
        String urlString = url.toString();

        // Verify the string format
        assertTrue(urlString.startsWith("nostrconnect://3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d?"));
        assertTrue(urlString.contains("relay=wss%3A%2F%2Frelay.example.com"));
        assertTrue(urlString.contains("relay=wss%3A%2F%2Frelay2.example.com"));
        assertTrue(urlString.contains("secret=mySecretToken123"));
        assertTrue(urlString.contains("perms=sign_event%2Cget_public_key"));
        assertTrue(urlString.contains("name=Test+App"));
        assertTrue(urlString.contains("url=https%3A%2F%2Ftestapp.example.com"));
        assertTrue(urlString.contains("image=https%3A%2F%2Ftestapp.example.com%2Flogo.png"));
    }

    @Test
    public void testParse() throws MalformedURLException, URISyntaxException, UnsupportedEncodingException {
        // Create a URL string
        String urlString =
            "nostrconnect://3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d" +
            "?relay=wss%3A%2F%2Frelay.example.com" +
            "&relay=wss%3A%2F%2Frelay2.example.com" +
            "&secret=mySecretToken123" +
            "&perms=sign_event%2Cget_public_key" +
            "&name=Test+App" +
            "&url=https%3A%2F%2Ftestapp.example.com" +
            "&image=https%3A%2F%2Ftestapp.example.com%2Flogo.png";

        // Parse the URL
        NostrconnectUrl url = NostrconnectUrl.parse(urlString);

        // Verify all fields
        assertEquals("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", url.clientPubKey.asHex());
        assertEquals(2, url.relays.size());
        assertTrue(url.relays.contains("wss://relay.example.com"));
        assertTrue(url.relays.contains("wss://relay2.example.com"));
        assertEquals("mySecretToken123", url.secret);
        assertEquals("Test App", url.metadata.getName());
        assertEquals("https://testapp.example.com", url.metadata.getUrl());
        assertEquals("https://testapp.example.com/logo.png", url.metadata.getImage());
        assertEquals(2, url.metadata.getPerms().size());
        assertTrue(url.metadata.getPerms().contains("sign_event"));
        assertTrue(url.metadata.getPerms().contains("get_public_key"));
    }

    @Test
    public void testRoundTrip() throws MalformedURLException, URISyntaxException, UnsupportedEncodingException {
        // Create a NostrconnectUrl object
        NostrconnectUrl originalUrl = new NostrconnectUrl(testPubKey, testRelays, testSecret, testMetadata);

        // Convert to string
        String urlString = originalUrl.toString();

        // Parse back to object
        NostrconnectUrl parsedUrl = NostrconnectUrl.parse(urlString);

        // Verify all fields match
        assertEquals(originalUrl.clientPubKey.asHex(), parsedUrl.clientPubKey.asHex());
        assertEquals(originalUrl.relays.size(), parsedUrl.relays.size());
        for (String relay : originalUrl.relays) {
            assertTrue(parsedUrl.relays.contains(relay));
        }
        assertEquals(originalUrl.secret, parsedUrl.secret);
        assertEquals(originalUrl.metadata.getName(), parsedUrl.metadata.getName());
        assertEquals(originalUrl.metadata.getUrl(), parsedUrl.metadata.getUrl());
        assertEquals(originalUrl.metadata.getImage(), parsedUrl.metadata.getImage());
        assertEquals(originalUrl.metadata.getPerms().size(), parsedUrl.metadata.getPerms().size());
        for (String perm : originalUrl.metadata.getPerms()) {
            assertTrue(parsedUrl.metadata.getPerms().contains(perm));
        }
    }

    @Test(expected = MalformedURLException.class)
    public void testParseNoRelay() throws MalformedURLException, URISyntaxException, UnsupportedEncodingException {
        // Create a URL string with no relay
        String urlString =
            "nostrconnect://3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d" + "?secret=mySecretToken123";

        // This should throw MalformedURLException
        NostrconnectUrl.parse(urlString);
    }

    @Test(expected = MalformedURLException.class)
    public void testParseNoSecret() throws MalformedURLException, URISyntaxException, UnsupportedEncodingException {
        // Create a URL string with no secret
        String urlString =
            "nostrconnect://3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d" +
            "?relay=wss%3A%2F%2Frelay.example.com";

        // This should throw MalformedURLException
        NostrconnectUrl.parse(urlString);
    }

    @Test
    public void testClone() {
        // Create a NostrconnectUrl object
        NostrconnectUrl originalUrl = new NostrconnectUrl(testPubKey, testRelays, testSecret, testMetadata);

        // Clone it
        NostrconnectUrl clonedUrl = originalUrl.clone();

        // Verify all fields match
        assertEquals(originalUrl.clientPubKey, clonedUrl.clientPubKey);
        assertEquals(originalUrl.relays, clonedUrl.relays);
        assertEquals(originalUrl.secret, clonedUrl.secret);
        assertEquals(originalUrl.metadata, clonedUrl.metadata);
    }

    @Test
    public void testMinimalMetadata() throws MalformedURLException, URISyntaxException, UnsupportedEncodingException {
        // Create minimal metadata
        Nip46AppMetadata minimalMetadata = new Nip46AppMetadata(null, null, null, null);
        NostrconnectUrl url = new NostrconnectUrl(testPubKey, testRelays, testSecret, minimalMetadata);

        // Convert to string and parse back
        String urlString = url.toString();
        NostrconnectUrl parsedUrl = NostrconnectUrl.parse(urlString);

        // Verify fields
        assertNull(parsedUrl.metadata.getName());
        assertNull(parsedUrl.metadata.getUrl());
        assertNull(parsedUrl.metadata.getImage());
        assertNull(parsedUrl.metadata.getPerms());
    }
}
