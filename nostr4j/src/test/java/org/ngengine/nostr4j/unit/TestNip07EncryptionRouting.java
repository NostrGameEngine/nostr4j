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
package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import org.junit.Test;
import org.ngengine.nostr4j.signer.NostrNIP07Signer;
import org.ngengine.nostr4j.signer.NostrSigner;

public class TestNip07EncryptionRouting {

    @Test
    public void encryptionAlgorithmsUseSeparateExtensionNamespaces() throws Exception {
        NostrNIP07Signer signer = new NostrNIP07Signer();
        try {
            Method routing = NostrNIP07Signer.class.getDeclaredMethod("getEncFun", NostrSigner.EncryptAlgo.class, String.class);
            routing.setAccessible(true);
            assertEquals("window.nostr.nip04.encrypt", routing.invoke(signer, NostrSigner.EncryptAlgo.NIP04, "encrypt"));
            assertEquals("window.nostr.nip04.decrypt", routing.invoke(signer, NostrSigner.EncryptAlgo.NIP04, "decrypt"));
            assertEquals("window.nostr.nip44.encrypt", routing.invoke(signer, NostrSigner.EncryptAlgo.NIP44, "encrypt"));
            assertEquals("window.nostr.nip44.decrypt", routing.invoke(signer, NostrSigner.EncryptAlgo.NIP44, "decrypt"));
        } finally {
            signer.close().await();
        }
    }
}
