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
package org.ngengine.nostr4j.keypair;

import java.io.Serializable;

public class NostrKeyPair implements Serializable, Cloneable {

    private NostrPublicKey publicKey;
    private NostrPrivateKey privateKey;

    public NostrKeyPair(NostrPrivateKey privateKey, NostrPublicKey publicKey)
        throws Exception {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public NostrKeyPair(NostrPrivateKey privateKey) throws Exception {
        this(privateKey, privateKey.getPublicKey());
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
        return (
            "KeyPair{" +
            "publicKey=" +
            publicKey +
            ", privateKey=" +
            privateKey +
            '}'
        );
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
        if (
            this.publicKey != other.publicKey &&
            (this.publicKey == null || !this.publicKey.equals(other.publicKey))
        ) {
            return false;
        }
        if (
            this.privateKey != other.privateKey &&
            (
                this.privateKey == null ||
                !this.privateKey.equals(other.privateKey)
            )
        ) {
            return false;
        }
        return true;
    }
}
