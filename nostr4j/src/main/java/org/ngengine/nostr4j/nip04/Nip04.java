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

package org.ngengine.nostr4j.nip04;

import java.nio.charset.StandardCharsets;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class Nip04 {

    private static final AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor(Nip04.class);

    public static String encryptSync(String plaintext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        byte pub[] = concatBytes(0x02, theirPublicKey._array());
        byte[] shared = NGEPlatform.get().secp256k1SharedSecret(ourPrivateKey._array(), pub);

        byte[] sharedX = new byte[32];
        System.arraycopy(shared, 1, sharedX, 0, 32);

        byte[] iv = NGEPlatform.get().randomBytes(16);
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = NGEPlatform.get().aes256cbc(sharedX, iv, data, true);
        String b64ciphertext = NGEPlatform.get().base64encode(ciphertext);
        String b64iv = NGEPlatform.get().base64encode(iv);
        return b64ciphertext + "?iv=" + b64iv;
    }

    public static AsyncTask<String> encrypt(String plaintext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        return executor.run(() -> {
            return encryptSync(plaintext, ourPrivateKey, theirPublicKey);
        });
    }

    private static byte[] concatBytes(int prefix, byte[] array) {
        byte[] result = new byte[array.length + 1];
        result[0] = (byte) prefix;
        System.arraycopy(array, 0, result, 1, array.length);
        return result;
    }

    public static String decryptSync(String ciphertext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        String[] parts = ciphertext.split("\\?iv=");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid ciphertext format");
        }
        byte[] iv = NGEPlatform.get().base64decode(parts[1]);
        byte[] data = NGEPlatform.get().base64decode(parts[0]);
        byte pub[] = concatBytes(0x02, theirPublicKey._array());
        byte[] shared = NGEPlatform.get().secp256k1SharedSecret(ourPrivateKey._array(), pub);

        byte[] sharedX = new byte[32];
        System.arraycopy(shared, 1, sharedX, 0, 32);

        byte[] plaintext = NGEPlatform.get().aes256cbc(sharedX, iv, data, false);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    public static AsyncTask<String> decrypt(String ciphertext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        return executor.run(() -> {
            return decryptSync(ciphertext, ourPrivateKey, theirPublicKey);
        });
    }
}
