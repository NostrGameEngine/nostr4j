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

import java.nio.ByteBuffer;
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
        ByteBuffer pub = prefixedPublicKey(theirPublicKey);
        ByteBuffer shared = NGEPlatform.get().secp256k1SharedSecret(ourPrivateKey.asReadOnlyBuffer(), pub);
        ByteBuffer sharedX = range(shared, 1, 32);
        ByteBuffer iv = NGEPlatform.get().randomBytesBuffer(16);
        ByteBuffer data = utf8(plaintext);
        ByteBuffer ciphertext = NGEPlatform.get().aes256cbc(sharedX, iv, data, true);
        String b64ciphertext = NGEPlatform.get().base64encode(ciphertext);
        String b64iv = NGEPlatform.get().base64encode(iv);
        return b64ciphertext + "?iv=" + b64iv;
    }

    public static AsyncTask<String> encrypt(String plaintext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        return executor.run(() -> {
            return encryptSync(plaintext, ourPrivateKey, theirPublicKey);
        });
    }

    private static ByteBuffer prefixedPublicKey(NostrPublicKey publicKey) {
        ByteBuffer key = publicKey.asReadOnlyBuffer();
        ByteBuffer result = NGEPlatform.get().getNativeAllocator().malloc(key.remaining() + 1);
        result.put((byte) 0x02);
        result.put(key);
        result.flip();
        return result;
    }

    public static String decryptSync(String ciphertext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        String[] parts = ciphertext.split("\\?iv=");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid ciphertext format");
        }
        ByteBuffer iv = NGEPlatform.get().base64decodeBuffer(parts[1]);
        ByteBuffer data = NGEPlatform.get().base64decodeBuffer(parts[0]);
        ByteBuffer pub = prefixedPublicKey(theirPublicKey);
        ByteBuffer shared = NGEPlatform.get().secp256k1SharedSecret(ourPrivateKey.asReadOnlyBuffer(), pub);
        ByteBuffer sharedX = range(shared, 1, 32);
        ByteBuffer plaintext = NGEPlatform.get().aes256cbc(sharedX, iv, data, false);
        return new String(toByteArray(plaintext), StandardCharsets.UTF_8);
    }

    public static AsyncTask<String> decrypt(String ciphertext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        return executor.run(() -> {
            return decryptSync(ciphertext, ourPrivateKey, theirPublicKey);
        });
    }

    private static ByteBuffer range(ByteBuffer source, int offset, int length) {
        ByteBuffer view = source.duplicate();
        view.position(offset);
        view.limit(offset + length);
        return view.slice();
    }

    private static byte[] toByteArray(ByteBuffer source) {
        ByteBuffer view = source.slice();
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    private static ByteBuffer utf8(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer output = NGEPlatform.get().getNativeAllocator().malloc(encoded.length);
        output.put(encoded);
        output.flip();
        return output;
    }
}
