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
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;

public interface NostrSigner extends Cloneable, Serializable {
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
     * @return an async task that will be completed with the encrypted message
     */
    AsyncTask<String> encrypt(String message, NostrPublicKey publicKey);
    /**
     * Decrypt a message
     * @param message the message to decrypt
     * @param publicKey the public key of the sender
     * @return an async task that will be completed with the decrypted message
     */
    AsyncTask<String> decrypt(String message, NostrPublicKey publicKey);

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
}
