package org.ngengine.nostr4j.signer;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.util.function.Function;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;

/**
 * Signs each event with a newly generated key pair.
 * Calls to sign() and powSign() use independent keys, which are destroyed when the operation completes.
 * Use NostrKeyPairSigner when multiple operations need to share the same identity.
 */
public final class NostrAnonSigner implements NostrSigner {

    private static final long serialVersionUID = 1L;

    /**
     * Create an ephemeral signer whose identity remains stable across a compound operation.
     * Close the action when signing, encryption and receipt binding are complete.
     *
     * @return a new action with its own non-persistent key pair
     */
    public Action newAction() {
        return new Action();
    }

    @Override
    public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
        return signWithNewKey(signer -> signer.sign(event));
    }

    @Override
    public AsyncTask<SignedNostrEvent> powSign(UnsignedNostrEvent event, int difficulty) {
        return signWithNewKey(signer -> signer.powSign(event, difficulty));
    }

    private AsyncTask<SignedNostrEvent> signWithNewKey(Function<NostrKeyPairSigner, AsyncTask<SignedNostrEvent>> operation) {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            AsyncTask<SignedNostrEvent> task = operation
                .apply(signer)
                .then(signed -> {
                    signer.getKeyPair().destroy();
                    return signed;
                });
            task.catchException(error -> signer.getKeyPair().destroy());
            return task;
        } catch (RuntimeException error) {
            signer.getKeyPair().destroy();
            return AsyncTask.failed(error);
        }
    }

    @Override
    public AsyncTask<NostrPublicKey> getPublicKey() {
        return AsyncTask.failed(new UnsupportedOperationException("Anonymous signer has no persistent public key"));
    }

    @Override
    public AsyncTask<Boolean> isAvailable() {
        return AsyncTask.completed(true);
    }

    @Override
    public AsyncTask<String> encrypt(String text, NostrPublicKey peer, EncryptAlgo algo) {
        return AsyncTask.failed(new UnsupportedOperationException("Anonymous signer does not retain encryption keys"));
    }

    @Override
    public AsyncTask<String> decrypt(String text, NostrPublicKey peer, EncryptAlgo algo) {
        return AsyncTask.failed(new UnsupportedOperationException("Anonymous signer does not retain decryption keys"));
    }

    @Override
    public AsyncTask<NostrSigner> close() {
        return AsyncTask.completed(this);
    }

    /**
     * An ephemeral signing identity for operations that need to reuse one key pair.
     * Closing the action destroys its secret and prevents further operations.
     * Serialization is rejected so the private key cannot be persisted accidentally.
     */
    public static final class Action implements NostrSigner {

        private static final long serialVersionUID = 1L;
        private transient NostrKeyPairSigner signer = NostrKeyPairSigner.generate();

        private Action() {}

        private synchronized NostrKeyPairSigner active() {
            if (signer == null) throw new IllegalStateException("Anonymous action is closed");
            return signer;
        }

        @Override
        public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
            return active().sign(event);
        }

        @Override
        public AsyncTask<NostrPublicKey> getPublicKey() {
            return active().getPublicKey();
        }

        @Override
        public AsyncTask<String> encrypt(String text, NostrPublicKey peer, EncryptAlgo algo) {
            return active().encrypt(text, peer, algo);
        }

        @Override
        public AsyncTask<String> decrypt(String text, NostrPublicKey peer, EncryptAlgo algo) {
            return active().decrypt(text, peer, algo);
        }

        @Override
        public synchronized AsyncTask<NostrSigner> close() {
            if (signer != null) {
                signer.getKeyPair().destroy();
                signer = null;
            }
            return AsyncTask.completed(this);
        }

        private void writeObject(ObjectOutputStream output) throws IOException {
            throw new NotSerializableException("Anonymous action keys must never be persisted");
        }

        @Override
        public String toString() {
            return "Anonymous action (ephemeral)";
        }
    }
}
