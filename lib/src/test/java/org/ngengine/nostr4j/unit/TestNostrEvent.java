package org.ngengine.nostr4j.unit;

import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.platform.jvm.JVMAsyncPlatform;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.nostr4j.utils.NostrUtils;
import org.ngengine.nostr4j.utils.Bech32.Bech32Exception;

import static org.junit.Assert.*;

public class TestNostrEvent {
    
    @Test
    public void testSigning() throws Bech32Exception, Exception{
        JVMAsyncPlatform._NO_AUX_RANDOM = true;
        String nsec = "nsec1v92q43n3ywpmp2p9nuaqmrrsa095rfys28p0rejn47vcqvktytxqaezlcl";
        String noteId = "note164s5jz53dt0kt4ralz96phw7kstvrzzdw0waxdyu5epqn7ssdg0su6wzjn";
        String signature = "7f22aaf0dca933fcbc85bcb3a30cadb1537c250b8d0331b76169a374a78619798c69756dffb321b60861e8e9ccc902b2e5f0c7234274d209cf4cdbae2ee73ae8";
        NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair(
            NostrPrivateKey.fromBech32(nsec)
        ));
        UnsignedNostrEvent event = new UnsignedNostrEvent()
            .setContent("test123")
            .setKind(1)
            .setCreatedAt(1742147457);
        System.out.println(event.toString());
        SignedNostrEvent signedEvent = signer.sign(event);
        assertTrue(signedEvent.verify());
        
        assertEquals(NostrUtils.bytesToHex(Bech32.bech32Decode(noteId)), signedEvent.getId());
        assertEquals(noteId, signedEvent.getIdBech32());
        assertEquals(signature,signedEvent.getSignature());      

    }

    @Test
    public void testSigningAUXRandom() throws Bech32Exception, Exception {
        JVMAsyncPlatform._NO_AUX_RANDOM = false;
        String nsec = "nsec1v92q43n3ywpmp2p9nuaqmrrsa095rfys28p0rejn47vcqvktytxqaezlcl";
        String noteId = "note164s5jz53dt0kt4ralz96phw7kstvrzzdw0waxdyu5epqn7ssdg0su6wzjn";
        String signature = "7f22aaf0dca933fcbc85bcb3a30cadb1537c250b8d0331b76169a374a78619798c69756dffb321b60861e8e9ccc902b2e5f0c7234274d209cf4cdbae2ee73ae8";

        NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair(
                NostrPrivateKey.fromBech32(nsec)));
        UnsignedNostrEvent event = new UnsignedNostrEvent()
                .setContent("test123")
                .setKind(1)
                .setCreatedAt(1742147457);
        System.out.println(event.toString());
        SignedNostrEvent signedEvent = signer.sign(event);
        assertTrue(signedEvent.verify());
        assertEquals(NostrUtils.bytesToHex(Bech32.bech32Decode(noteId)), signedEvent.getId());
        assertEquals(noteId, signedEvent.getIdBech32());

        // aux random signature is not equal to the hardcoded one
        assertNotEquals(signature, signedEvent.getSignature());

    }
}
