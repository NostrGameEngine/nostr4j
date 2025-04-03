package org.ngengine.nostr4j.signer;

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrKeyPairSigner implements NostrSigner {
    private final NostrKeyPair keyPair;

    public NostrKeyPairSigner(NostrKeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static NostrKeyPairSigner generate() throws Exception {
        return new NostrKeyPairSigner(new NostrKeyPair());
    }

    @Override
    public SignedNostrEvent sign(UnsignedNostrEvent event) throws Exception {
        String id = NostrEvent.computeEventId(keyPair.getPublicKey().asHex(), event);
        String sig = NostrUtils.getPlatform().sign(id, keyPair.getPrivateKey());
        return new SignedNostrEvent(
            id,
            keyPair.getPublicKey(),
            event.getKind(),
            event.getContent(),
            event.getCreatedAt(),
            sig,
            event.listTags()
        );        
    }

    @Override
    public String encrypt(String message, NostrPublicKey publicKey) throws Exception {
        byte[] sharedKey = Nip44.getConversationKey(keyPair.getPrivateKey(), publicKey);
        return Nip44.encrypt(message, sharedKey);
    }

    @Override
    public String decrypt(String message, NostrPublicKey publicKey) throws Exception {
        byte[] sharedKey = Nip44.getConversationKey(keyPair.getPrivateKey(), publicKey);
        return Nip44.decrypt(message, sharedKey);
    }

    @Override
    public NostrPublicKey getPublicKey() {
        return keyPair.getPublicKey();
    }


    @Override
    public NostrKeyPairSigner clone() throws CloneNotSupportedException {
        try {
            return new NostrKeyPairSigner(keyPair.clone());
        } catch (Exception e) {
            throw new CloneNotSupportedException();
        }
    }

    @Override
    public String toString() {
        return "NostrKeyPairSigner{" + "keyPair=" + keyPair + '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final NostrKeyPairSigner other = (NostrKeyPairSigner) obj;
        if (this.keyPair != other.keyPair && (this.keyPair == null || !this.keyPair.equals(other.keyPair))) {
            return false;
        }
        return true;
    }


    public NostrKeyPair getKeyPair() {
        return keyPair;
    }

    
}
