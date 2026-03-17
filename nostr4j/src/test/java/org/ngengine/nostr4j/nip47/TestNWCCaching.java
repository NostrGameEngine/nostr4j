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
package org.ngengine.nostr4j.nip47;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;

public class TestNWCCaching {

    @Test
    public void testNwcUriSnapshotsRelayList() throws Exception {
        List<String> relays = new ArrayList<>();
        relays.add("wss://relay-one.example");

        NWCUri uri = new NWCUri(
            NostrPublicKey.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
            relays,
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            null
        );

        relays.add("wss://relay-two.example");

        assertEquals(List.of("wss://relay-one.example"), uri.getRelays());
        try {
            uri.getRelays().add("wss://relay-three.example");
            fail("Expected relay list to be immutable");
        } catch (UnsupportedOperationException expected) {}
    }

    @Test
    public void testSupportedMethodsCacheReturnsImmutableList() throws Exception {
        SignedNostrEvent event = new SignedNostrEvent(
            "abc",
            NostrPublicKey.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
            NWCWallet.INFO_KIND,
            "pay_invoice make_invoice",
            Instant.ofEpochSecond(1742147457L),
            "sig",
            List.of()
        );

        NostrPool pool = new NostrPool() {
            @Override
            public AsyncTask<List<SignedNostrEvent>> fetch(NostrFilter filter, int numEvents, Duration timeout) {
                return AsyncTask.completed(List.of(event));
            }
        };

        NWCWallet wallet = new NWCWallet(
            pool,
            new NWCUri(
                NostrPublicKey.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                List.of("wss://relay.example"),
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                null
            )
        );

        AsyncTask<List<String>> firstTask = wallet.getSupportedMethods();
        AsyncTask<List<String>> secondTask = wallet.getSupportedMethods();
        List<String> methods = firstTask.await();

        assertSame(firstTask, secondTask);
        assertEquals(List.of("pay_invoice", "make_invoice"), methods);
        try {
            methods.add("lookup_invoice");
            fail("Expected supported methods to be immutable");
        } catch (UnsupportedOperationException expected) {}
    }
}
