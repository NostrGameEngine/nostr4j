package org.ngengine.nostr4j.signer;

import java.io.Serializable;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

public interface NostrSigner extends Cloneable,Serializable{
    public SignedNostrEvent sign(UnsignedNostrEvent event) throws Exception;
    public String encrypt(String message, NostrPublicKey publicKey)  throws Exception;
    public String decrypt(String message, NostrPublicKey publicKey)  throws Exception;
    public NostrPublicKey getPublicKey();    
}
