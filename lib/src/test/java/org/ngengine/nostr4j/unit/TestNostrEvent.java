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
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.platform.jvm.JVMAsyncPlatform;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.nostr4j.utils.Bech32.Bech32Exception;
import org.ngengine.nostr4j.utils.NostrUtils;

public class TestNostrEvent {

    @Test
    public void testSigning() throws Bech32Exception, Exception {
        JVMAsyncPlatform._NO_AUX_RANDOM = true;
        String nsec = "nsec1v92q43n3ywpmp2p9nuaqmrrsa095rfys28p0rejn47vcqvktytxqaezlcl";
        String noteId = "note164s5jz53dt0kt4ralz96phw7kstvrzzdw0waxdyu5epqn7ssdg0su6wzjn";
        String signature =
            "7f22aaf0dca933fcbc85bcb3a30cadb1537c250b8d0331b76169a374a78619798c69756dffb321b60861e8e9ccc902b2e5f0c7234274d209cf4cdbae2ee73ae8";
        NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair(NostrPrivateKey.fromBech32(nsec)));
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .setContent("test123")
            .setKind(1)
            .setCreatedAt(Instant.ofEpochSecond(1742147457));
        System.out.println(event.toString());
        SignedNostrEvent signedEvent = signer.sign(event).await();
        assertTrue(signedEvent.verify());

        assertEquals(NostrUtils.bytesToHex(Bech32.bech32Decode(noteId)), signedEvent.getId());
        assertEquals(noteId, signedEvent.getIdBech32());
        assertEquals(signature, signedEvent.getSignature());
    }

    @Test
    public void testSigningAUXRandom() throws Bech32Exception, Exception {
        JVMAsyncPlatform._NO_AUX_RANDOM = false;
        String nsec = "nsec1v92q43n3ywpmp2p9nuaqmrrsa095rfys28p0rejn47vcqvktytxqaezlcl";
        String noteId = "note164s5jz53dt0kt4ralz96phw7kstvrzzdw0waxdyu5epqn7ssdg0su6wzjn";
        String signature =
            "7f22aaf0dca933fcbc85bcb3a30cadb1537c250b8d0331b76169a374a78619798c69756dffb321b60861e8e9ccc902b2e5f0c7234274d209cf4cdbae2ee73ae8";

        NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair(NostrPrivateKey.fromBech32(nsec)));
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .setContent("test123")
            .setKind(1)
            .setCreatedAt(Instant.ofEpochSecond(1742147457));
        System.out.println(event.toString());
        SignedNostrEvent signedEvent = signer.sign(event).await();
        assertTrue(signedEvent.verify());
        assertEquals(NostrUtils.bytesToHex(Bech32.bech32Decode(noteId)), signedEvent.getId());
        assertEquals(noteId, signedEvent.getIdBech32());

        // aux random signature is not equal to the hardcoded one
        assertNotEquals(signature, signedEvent.getSignature());
    }
}
