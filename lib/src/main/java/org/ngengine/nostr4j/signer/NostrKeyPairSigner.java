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

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.platform.AsyncTask;
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
    public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
        String id = NostrEvent.computeEventId(keyPair.getPublicKey().asHex(), event);
        return NostrUtils
            .getPlatform()
            .signAsync(id, keyPair.getPrivateKey())
            .then(sig -> {
                return new SignedNostrEvent(
                    id,
                    keyPair.getPublicKey(),
                    event.getKind(),
                    event.getContent(),
                    event.getCreatedAt(),
                    sig,
                    event.listTags()
                );
            });
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
