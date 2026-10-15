package org.ngengine.nostr4j.signer;

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
}
