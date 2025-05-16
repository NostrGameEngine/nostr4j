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

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.proto.NostrMessage;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.jvm.JVMAsyncPlatform;

public class TestNostrEvent {

    @Test
    public void testSigning() throws Exception {
        JVMAsyncPlatform._NO_AUX_RANDOM = true;
        String nsec = "nsec1v92q43n3ywpmp2p9nuaqmrrsa095rfys28p0rejn47vcqvktytxqaezlcl";
        String noteId = "note164s5jz53dt0kt4ralz96phw7kstvrzzdw0waxdyu5epqn7ssdg0su6wzjn";
        String signature =
            "7f22aaf0dca933fcbc85bcb3a30cadb1537c250b8d0331b76169a374a78619798c69756dffb321b60861e8e9ccc902b2e5f0c7234274d209cf4cdbae2ee73ae8";
        NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair(NostrPrivateKey.fromBech32(nsec)));
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withContent("test123")
            .withKind(1)
            .createdAt(Instant.ofEpochSecond(1742147457));
        System.out.println(event.toString());
        SignedNostrEvent signedEvent = signer.sign(event).await();
        assertTrue(signedEvent.verify());

        assertEquals(NGEUtils.bytesToHex(Bech32.bech32Decode(noteId)), signedEvent.getId());
        assertEquals(noteId, signedEvent.getIdBech32());
        assertEquals(signature, signedEvent.getSignature());
    }

    @Test
    public void testSigningAUXRandom() throws Exception {
        JVMAsyncPlatform._NO_AUX_RANDOM = false;
        String nsec = "nsec1v92q43n3ywpmp2p9nuaqmrrsa095rfys28p0rejn47vcqvktytxqaezlcl";
        String noteId = "note164s5jz53dt0kt4ralz96phw7kstvrzzdw0waxdyu5epqn7ssdg0su6wzjn";
        String signature =
            "7f22aaf0dca933fcbc85bcb3a30cadb1537c250b8d0331b76169a374a78619798c69756dffb321b60861e8e9ccc902b2e5f0c7234274d209cf4cdbae2ee73ae8";

        NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair(NostrPrivateKey.fromBech32(nsec)));
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withContent("test123")
            .withKind(1)
            .createdAt(Instant.ofEpochSecond(1742147457));
        System.out.println(event.toString());
        SignedNostrEvent signedEvent = signer.sign(event).await();
        assertTrue(signedEvent.verify());
        assertEquals(NGEUtils.bytesToHex(Bech32.bech32Decode(noteId)), signedEvent.getId());
        assertEquals(noteId, signedEvent.getIdBech32());

        // aux random signature is not equal to the hardcoded one
        assertNotEquals(signature, signedEvent.getSignature());
    }

    public void assertVerify(NostrEvent event) {
        assertEquals(event.getKind(), 1);
        assertEquals(event.getContent(), "test123");
        assertEquals(event.getTags().get("a").get(0), "1");

        assertEquals(event.getTags().get("b").get(0), "1");
        assertEquals(event.getTags().get("b").get(1), "2");
        assertEquals(event.getTags().get("b").get(2), "3");

        assertEquals(event.getTags().get("c"), null);

        assertEquals(event.getTagValues("b").get(0), "1");
        assertEquals(event.getTagValues("b").get(1), "2");
        assertEquals(event.getTagValues("b").get(2), "3");

        assertEquals(event.getTagValues("c"), null);

        assertEquals(event.getTagValues("expiration").get(0), "1742147457");

        assertEquals(event.getCreatedAt().getEpochSecond(), 1742147457);

        assertEquals(event.getExpiration().getEpochSecond(), 1742147457);

        assertEquals(event.getTagRows().size(), 3);

        Collection<List<String>> tagRows = event.getTagRows();
        Iterator<List<String>> it = tagRows.iterator();
        List<String> row = it.next();
        assertEquals(row.get(0), "a");
        assertEquals(row.get(1), "1");
        row = it.next();
        assertEquals(row.get(0), "b");
        assertEquals(row.get(1), "1");
        assertEquals(row.get(2), "2");
        assertEquals(row.get(3), "3");
        row = it.next();
        assertEquals(row.get(0), "expiration");
        assertEquals(row.get(1), "1742147457");
    }

    @Test
    public void testEventSerialization() throws Exception {
        JVMAsyncPlatform._NO_AUX_RANDOM = true;
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .withContent("test123")
            .withKind(1)
            .withTag("a", "1")
            .withTag("b", "1", "2", "3")
            .withTag("c", "3")
            .withoutTag("c")
            .withExpiration(Instant.ofEpochSecond(1742147457))
            .createdAt(Instant.ofEpochSecond(1742147457));

        assertVerify(event);

        NostrSigner signer = new NostrKeyPairSigner(
            new NostrKeyPair(NostrPrivateKey.fromBech32("nsec1ksrsh0gvc7ug848ec5u0qj604ga47qhafl9de5rvdx292mkq9p0ss7w60k"))
        );
        SignedNostrEvent signedEvent = signer.sign(event).await();
        assertTrue(signedEvent.verify());
        assertVerify(signedEvent);

        String json = NostrMessage.toJSON(signedEvent);
        String expectedJson =
            "[\"EVENT\",{\"sig\":\"2270df3c1461aab13e63ec36426f5d5d9f8d4435c4ab0c5717b7b533f2aba02126cc56349f7c98b234f7b5525b00e62dfd336ff6f10770ea097304406968ab16\",\"kind\":1,\"created_at\":1742147457,\"id\":\"a908e34b0bef12578b983477791ad4c93ed8f115d5b339ab89503fc321078f75\",\"content\":\"test123\",\"pubkey\":\"c56a8bec9a793b9b6be37a0d14e89e4c23a6d9a61b016d16e2f8df90254a63d4\",\"tags\":[[\"a\",\"1\"],[\"b\",\"1\",\"2\",\"3\"],[\"expiration\",\"1742147457\"]]}]";

        System.out.println(json);
        assertEquals(json.trim(), expectedJson.trim());
    }
}
