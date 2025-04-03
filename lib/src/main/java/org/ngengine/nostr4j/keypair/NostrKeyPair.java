package org.ngengine.nostr4j.keypair;

import java.io.Serializable;

public class NostrKeyPair implements Serializable, Cloneable {
    private NostrPublicKey publicKey;
    private NostrPrivateKey privateKey;

    public NostrKeyPair(NostrPrivateKey privateKey, NostrPublicKey publicKey) throws Exception {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public NostrKeyPair(NostrPrivateKey privateKey) throws Exception {
        this(privateKey,privateKey.getPublicKey());
    }

    public NostrKeyPair() throws Exception {
        NostrPrivateKey privateKey = NostrPrivateKey.generate();
        this.publicKey = privateKey.getPublicKey();
        this.privateKey = privateKey;
    }

    public NostrPublicKey getPublicKey() {
        return this.publicKey;
    }

    public NostrPrivateKey getPrivateKey() {
        return this.privateKey;
    }
    

    @Override
    public NostrKeyPair clone() throws CloneNotSupportedException {
        try {
            return new NostrKeyPair(privateKey.clone(), publicKey.clone());
        } catch (Exception e) {
            throw new CloneNotSupportedException();
        }
    }

    @Override
    public String toString() {
        return "KeyPair{" + "publicKey=" + publicKey + ", privateKey=" + privateKey + '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final NostrKeyPair other = (NostrKeyPair) obj;
        if (this.publicKey != other.publicKey && (this.publicKey == null || !this.publicKey.equals(other.publicKey))) {
            return false;
        }
        if (this.privateKey != other.privateKey && (this.privateKey == null || !this.privateKey.equals(other.privateKey))) {
            return false;
        }
        return true;
    }

}
