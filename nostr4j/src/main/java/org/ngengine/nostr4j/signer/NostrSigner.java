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
package org.ngengine.nostr4j.signer;

import java.io.Serializable;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

public interface NostrSigner extends Cloneable, Serializable {
    public enum EncryptAlgo {
        NIP44,
        NIP04,
    }

    /**
     * Sign an event
     * @param event the event to sign
     * @return an async task that will be completed with the signed event
     */
    AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event);

    /**
     * Encrypt a message
     * @param message  the message to encrypt
     * @param publicKey the public key of the recipient
     * @param algo the encryption algorithm to use
     * @return an async task that will be completed with the encrypted message
     */
    AsyncTask<String> encrypt(String message, NostrPublicKey publicKey, EncryptAlgo algo);
    /**
     * Decrypt a message
     * @param message the message to decrypt
     * @param publicKey the public key of the sender
     * @param algo the encryption algorithm used to encrypt the message
     * @return an async task that will be completed with the decrypted message
     */
    AsyncTask<String> decrypt(String message, NostrPublicKey publicKey, EncryptAlgo algo);

    /**
     * Encrypt a message using the default NIP44 algorithm.
     *
     * @param message   the message to encrypt
     * @param publicKey the public key of the recipient
     * @return an async task that will be completed with the encrypted message
     */
    default AsyncTask<String> encrypt(String message, NostrPublicKey publicKey) {
        return this.encrypt(message, publicKey, EncryptAlgo.NIP44);
    }

    /**
     * Decrypt a message using the default NIP44 algorithm.
     *
     * @param message   the message to decrypt
     * @param publicKey the public key of the sender
     * @return an async task that will be completed with the decrypted message
     */
    default AsyncTask<String> decrypt(String message, NostrPublicKey publicKey) {
        return this.decrypt(message, publicKey, EncryptAlgo.NIP44);
    }

    /**
     * Get the public key of the signer
     * @return an async task that will be completed with the public key
     */

    AsyncTask<NostrPublicKey> getPublicKey();

    /**
     * Close the signer and terminate all its resources
     * @return an async task that will be completed when the signer is closed
     */
    AsyncTask<NostrSigner> close();

    /**
     * Sign an event and attach a proof of computational work to it.
     * Make sure to limit the maxium difficulty passed to this method, as it doesn't do any
     * validation on the difficulty parameter, it might run forever if the difficulty is too high.
     *
     * @param event the event to sign
     * @param difficulty the target difficulty for the proof of work
     * @return an async task that will be completed with the signed event containing the proof of work
     */
    default AsyncTask<SignedNostrEvent> powSign(UnsignedNostrEvent event, int difficulty) {
        return this.getPublicKey()
            .compose(pubkey -> {
                return NostrEvent.minePow(pubkey, event, difficulty);
            })
            .compose(mined -> {
                return this.sign(mined);
            });
    }

    default AsyncTask<Boolean> isAvailable() {
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                getPublicKey()
                    .then(pubkey -> {
                        res.accept(pubkey != null);
                        return null;
                    })
                    .catchException(err -> {
                        res.accept(false);
                    });
            });
    }
}
