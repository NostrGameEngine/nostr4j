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
package org.ngengine.nostr4j.nip49;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.bech32.Bech32;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

public class Nip49 {

    private static final byte[] HRP = "ncryptsec".getBytes(StandardCharsets.UTF_8);
    private static final int DEFAULT_MEMORY_LIMIT = 1024 * 1024 * 257;
    private static final int DEFAULT_LOGN = 16;
    private static final AsyncExecutor executor = NGEPlatform.get().newAsyncExecutor(Nip49.class);

    public static long getApproximatedMemoryRequirement(int logn) {
        long n = 1 << logn;
        long bytes = 128l * n * 8l;
        return bytes;
    }

    public static String encryptSync(NostrPrivateKey priv, String password) throws Nip49FailedException {
        return encryptSync(priv, password, DEFAULT_LOGN, DEFAULT_MEMORY_LIMIT);
    }

    public static boolean isEncrypted(String bech32) {
        return bech32.startsWith("ncryptsec");
    }

    public static String encryptSync(NostrPrivateKey priv, String password, int logn, int memoryLimitBytes)
        throws Nip49FailedException {
        try {
            NGEPlatform platform = NGEPlatform.get();

            byte privateKey[] = priv._array();

            if (logn < 1) {
                throw new IllegalArgumentException("logn must be be >= 1");
            }
            if (getApproximatedMemoryRequirement(logn) > memoryLimitBytes) {
                throw new IllegalArgumentException("logn is too large for the specified memory limit");
            }

            byte normalizedPassword[] = platform.nfkc(password).getBytes(StandardCharsets.UTF_8);

            byte[] salt = platform.randomBytes(16);
            byte keySecurityByte = (byte) priv.getKeySecurity().ordinal();
            byte[] nonce = platform.randomBytes(24);
            byte versionNumber = 0x02;

            int n = 1 << logn;

            byte[] symmetricKey = platform.scrypt(normalizedPassword, salt, n, 8, 1, 32);
            assert symmetricKey.length == 32;

            byte[] associatedData = new byte[] { keySecurityByte };

            byte cipherText[] = platform.xchacha20poly1305(symmetricKey, nonce, privateKey, associatedData, true);

            ByteBuffer concat = ByteBuffer.allocate(
                1 + 1 + salt.length + nonce.length + associatedData.length + cipherText.length
            );
            concat.put(versionNumber);
            concat.put((byte) logn);
            concat.put(salt);
            concat.put(nonce);
            concat.put(associatedData);
            concat.put(cipherText);
            assert concat.position() == concat.limit();
            concat.flip();
            return Bech32.bech32Encode(HRP, concat);
        } catch (Exception e) {
            throw new Nip49FailedException("Failed to encrypt", e);
        }
    }

    public static NostrPrivateKey decryptSync(String ncryptsec, String password) throws Nip49FailedException {
        return decryptSync(ncryptsec, password, DEFAULT_MEMORY_LIMIT);
    }

    public static NostrPrivateKey decryptSync(String ncryptsec, String password, int memoryLimitBytes)
        throws Nip49FailedException {
        try {
            NGEPlatform platform = NGEPlatform.get();
            if (!ncryptsec.startsWith("ncryptsec")) {
                throw new IllegalArgumentException("Invalid ncryptsec prefix");
            }
            byte normalizedPassword[] = platform.nfkc(password).getBytes(StandardCharsets.UTF_8);

            ByteBuffer decoded = Bech32.bech32Decode(ncryptsec);
            if (decoded.remaining() < 1) {
                throw new IllegalArgumentException("Invalid ncryptsec");
            }
            byte versionNumber = decoded.get();
            if (versionNumber != 0x02) {
                throw new IllegalArgumentException("Unsupported version number");
            }
            int logn = decoded.get();
            if (logn < 1 || logn > 30) {
                throw new IllegalArgumentException("logn must be between 1 and 30");
            }
            byte[] salt = new byte[16];
            decoded.get(salt);
            byte[] nonce = new byte[24];
            decoded.get(nonce);
            byte[] associatedData = new byte[1];
            decoded.get(associatedData);
            byte keySecurityByte = associatedData[0];
            NostrPrivateKey.KeySecurity keySecurity = NostrPrivateKey.KeySecurity.values()[keySecurityByte];

            if (getApproximatedMemoryRequirement(logn) > memoryLimitBytes) {
                throw new IllegalArgumentException("logn is too large for the specified memory limit");
            }

            int n = 1 << logn;
            byte[] symmetricKey = platform.scrypt(normalizedPassword, salt, n, 8, 1, 32);
            assert symmetricKey.length == 32;
            byte[] cipherText = new byte[decoded.remaining()];
            decoded.get(cipherText);
            byte[] privateKey = platform.xchacha20poly1305(symmetricKey, nonce, cipherText, associatedData, false);
            if (privateKey == null) {
                throw new IllegalArgumentException("Invalid password");
            }
            if (privateKey.length != 32) {
                throw new IllegalArgumentException("Invalid private key length");
            }
            NostrPrivateKey priv = NostrPrivateKey.fromBytes(privateKey);
            priv.setKeySecurity(keySecurity);
            return priv;
        } catch (Exception e) {
            throw new Nip49FailedException("Failed to decrypt", e);
        }
    }

    public static AsyncTask<String> encrypt(NostrPrivateKey priv, String password) {
        return encrypt(priv, password, DEFAULT_LOGN, DEFAULT_MEMORY_LIMIT);
    }

    public static AsyncTask<NostrPrivateKey> decrypt(String ncryptsec, String password) {
        return decrypt(ncryptsec, password, DEFAULT_MEMORY_LIMIT);
    }

    public static AsyncTask<NostrPrivateKey> decrypt(String ncryptsec, String password, int memoryLimitBytes) {
        return executor.run(() -> {
            return decryptSync(ncryptsec, password, memoryLimitBytes);
        });
    }

    public static AsyncTask<String> encrypt(NostrPrivateKey priv, String password, int logn, int memoryLimitBytes) {
        return executor.run(() -> {
            return encryptSync(priv, password, logn, memoryLimitBytes);
        });
    }
}
