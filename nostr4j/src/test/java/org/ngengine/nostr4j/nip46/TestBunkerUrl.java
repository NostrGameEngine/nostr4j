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

import static org.junit.Assert.assertEquals;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import org.junit.Test;

public class TestBunkerUrl {

    @Test
    public void testParse() throws MalformedURLException, UnsupportedEncodingException, URISyntaxException {
        String url =
            "bunker://528cf6cfa16ea0d25d8aa6e98063264c5ece212e637442f02ebfbc5531910dc7?relay=wss://relay.nsec.app&secret=123";
        BunkerUrl parsed = BunkerUrl.parse(url);
        assertEquals(parsed.pubkey.asHex(), "528cf6cfa16ea0d25d8aa6e98063264c5ece212e637442f02ebfbc5531910dc7");
        assertEquals(parsed.relays.get(0), "wss://relay.nsec.app");
        assertEquals(parsed.secret, "123");
        String serial = parsed.toString();
        BunkerUrl parsed2 = BunkerUrl.parse(serial);
        assertEquals(parsed2.pubkey.asHex(), "528cf6cfa16ea0d25d8aa6e98063264c5ece212e637442f02ebfbc5531910dc7");
        assertEquals(parsed2.relays.get(0), "wss://relay.nsec.app");
        assertEquals(parsed2.secret, "123");
    }

    @Test
    public void testMultiRelay() throws MalformedURLException, UnsupportedEncodingException, URISyntaxException {
        String url =
            "bunker://528cf6cfa16ea0d25d8aa6e98063264c5ece212e637442f02ebfbc5531910dc7?relay=wss://relay.nsec.app&secret=123&relay=wss://nostr.rblb.it";
        BunkerUrl parsed = BunkerUrl.parse(url);
        assertEquals(parsed.pubkey.asHex(), "528cf6cfa16ea0d25d8aa6e98063264c5ece212e637442f02ebfbc5531910dc7");
        assertEquals(parsed.relays.get(0), "wss://relay.nsec.app");
        assertEquals(parsed.relays.get(1), "wss://nostr.rblb.it");
        assertEquals(parsed.secret, "123");
        String serial = parsed.toString();
        BunkerUrl parsed2 = BunkerUrl.parse(serial);
        assertEquals(parsed2.pubkey.asHex(), "528cf6cfa16ea0d25d8aa6e98063264c5ece212e637442f02ebfbc5531910dc7");
        assertEquals(parsed2.relays.get(0), "wss://relay.nsec.app");
        assertEquals(parsed2.relays.get(1), "wss://nostr.rblb.it");
        assertEquals(parsed2.secret, "123");
    }
}
