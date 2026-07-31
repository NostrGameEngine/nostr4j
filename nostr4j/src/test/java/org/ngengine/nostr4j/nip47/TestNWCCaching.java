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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
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

            @Override
            public AsyncTask<NostrRelay> ensureRelay(String relay) {
                return AsyncTask.completed(null);
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

    @Test
    public void testNip44IsPreferredWhenAdvertised() throws Exception {
        assertEncryptionNegotiation("nip04 nip44_v2", NostrSigner.EncryptAlgo.NIP44, "nip44_v2");
    }

    @Test
    public void testExplicitNip04SupportIsHonored() throws Exception {
        assertEncryptionNegotiation("nip04", NostrSigner.EncryptAlgo.NIP04, "nip04");
    }

    @Test
    public void testMissingEncryptionTagUsesLegacyNip04() throws Exception {
        assertEncryptionNegotiation(null, NostrSigner.EncryptAlgo.NIP04, null);
    }

    private void assertEncryptionNegotiation(
        String advertisedEncryption,
        NostrSigner.EncryptAlgo expectedAlgorithm,
        String expectedRequestTag
    ) throws Exception {
        NostrPrivateKey walletPrivateKey = NostrPrivateKey.fromHex(
            "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"
        );
        NostrKeyPairSigner walletSigner = new NostrKeyPairSigner(new NostrKeyPair(walletPrivateKey));
        NWCUri uri = new NWCUri(
            walletPrivateKey.getPublicKey(),
            List.of("wss://relay.example"),
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            null
        );
        List<List<String>> infoTags = advertisedEncryption == null
            ? List.of()
            : List.of(List.of("encryption", advertisedEncryption));
        SignedNostrEvent infoEvent = new SignedNostrEvent(
            "info",
            walletPrivateKey.getPublicKey(),
            NWCWallet.INFO_KIND,
            "get_balance",
            Instant.ofEpochSecond(1742147457L),
            "sig",
            infoTags
        );
        NegotiatingPool pool = new NegotiatingPool(
            infoEvent,
            walletSigner,
            uri.getSigner().getPublicKey().await(),
            expectedAlgorithm
        );
        NWCWallet wallet = new NWCWallet(pool, uri);

        assertEquals(Long.valueOf(123), wallet.getBalance().await());
        assertEquals(expectedRequestTag, pool.publishedRequest.getFirstTagFirstValue("encryption"));
        assertEquals(1, pool.infoFetches);
    }

    private static final class NegotiatingPool extends NostrPool {

        private final SignedNostrEvent infoEvent;
        private final NostrKeyPairSigner walletSigner;
        private final NostrPublicKey clientPublicKey;
        private final NostrSigner.EncryptAlgo expectedAlgorithm;
        private SignedNostrEvent publishedRequest;
        private int infoFetches;

        private NegotiatingPool(
            SignedNostrEvent infoEvent,
            NostrKeyPairSigner walletSigner,
            NostrPublicKey clientPublicKey,
            NostrSigner.EncryptAlgo expectedAlgorithm
        ) {
            this.infoEvent = infoEvent;
            this.walletSigner = walletSigner;
            this.clientPublicKey = clientPublicKey;
            this.expectedAlgorithm = expectedAlgorithm;
        }

        @Override
        public AsyncTask<List<SignedNostrEvent>> fetch(NostrFilter filter, int numEvents, Duration timeout) {
            if (filter.getKinds().contains(NWCWallet.INFO_KIND)) {
                infoFetches++;
                return AsyncTask.completed(List.of(infoEvent));
            }

            try {
                String requestJson = walletSigner
                    .decrypt(publishedRequest.getContent(), clientPublicKey, expectedAlgorithm)
                    .await();
                assertTrue(requestJson.contains("\"method\":\"get_balance\""));
                String responseJson = "{\"result_type\":\"get_balance\",\"error\":null,\"result\":{\"balance\":123}}";
                String encryptedResponse = walletSigner.encrypt(responseJson, clientPublicKey, expectedAlgorithm).await();
                SignedNostrEvent response = new SignedNostrEvent(
                    "response",
                    walletSigner.getPublicKey().await(),
                    NWCWallet.RESPONSE_KIND,
                    encryptedResponse,
                    Instant.ofEpochSecond(1742147458L),
                    "sig",
                    List.of(List.of("e", publishedRequest.getId()), List.of("p", clientPublicKey.asHex()))
                );
                return AsyncTask.completed(List.of(response));
            } catch (Throwable error) {
                return AsyncTask.failed(error);
            }
        }

        @Override
        public List<AsyncTask<NostrMessageAck>> publish(SignedNostrEvent event) {
            publishedRequest = event;
            return Collections.emptyList();
        }

        @Override
        public AsyncTask<NostrRelay> ensureRelay(String relay) {
            return AsyncTask.completed(null);
        }
    }
}
