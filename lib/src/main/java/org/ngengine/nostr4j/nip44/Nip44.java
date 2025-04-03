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
package org.ngengine.nostr4j.nip44;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.utils.NostrUtils;

/**
 * NIP-44 encrypt/decrypt
 * thread-safe
 */
public class Nip44 {

    private static final int MIN_PLAINTEXT_SIZE = 0x0001;
    private static final int MAX_PLAINTEXT_SIZE = 0xFFFF;
    private static final byte VERSION_V2 = 0x02;
    private static final int MAC_SIZE = 32;
    private static final int NONCE_SIZE = 32;
    private static final int CONVERSATION_KEY_SIZE = 32;
    private static final int VERSION_SIZE = 1;
    private static final byte[] NIP44_V2_BYTES =
        "nip44-v2".getBytes(StandardCharsets.UTF_8);

    public static byte[] getConversationKey(
        NostrPrivateKey privateKey,
        NostrPublicKey publicKey
    ) throws Exception {
        byte pub[] = concatBytes(
            0x02,
            Integer.MIN_VALUE,
            publicKey._array(),
            null,
            null,
            -1
        );
        byte[] shared = NostrUtils
            .getPlatform()
            .secp256k1SharedSecret(privateKey._array(), pub);
        byte sharedX[] = Arrays.copyOfRange(shared, 1, 33);
        assert sharedX.length == CONVERSATION_KEY_SIZE;
        return NostrUtils.getPlatform().hkdf_extract(NIP44_V2_BYTES, sharedX);
    }

    private static byte[] safeNonce(byte[] nonce) {
        if (nonce == null) {
            nonce = NostrUtils.getPlatform().randomBytes(NONCE_SIZE);
            assert !NostrUtils.allZeroes(nonce);
        } else if (nonce.length != NONCE_SIZE) {
            throw new IllegalArgumentException("Nonce must be 32 bytes");
        }
        return nonce;
    }

    private static byte[][] getMessageKeys(
        byte[] conversationKey,
        byte[] nonce
    ) throws Exception {
        if (
            conversationKey == null ||
            conversationKey.length != CONVERSATION_KEY_SIZE
        ) throw new IllegalArgumentException(
            "Conversation key must be 32 bytes"
        );
        nonce = safeNonce(nonce);
        byte[] keys = NostrUtils
            .getPlatform()
            .hkdf_expand(conversationKey, nonce, 76);
        byte[] chachaKey = Arrays.copyOfRange(keys, 0, 32);
        byte[] chachaNonce = Arrays.copyOfRange(keys, 32, 44);
        byte[] hmacKey = Arrays.copyOfRange(keys, 44, 76);
        return new byte[][] { chachaKey, chachaNonce, hmacKey };
    }

    private static int calcPaddedLength(int length) {
        if (length < 1) throw new IllegalArgumentException(
            "Expected positive integer"
        );

        if (length <= 32) return 32;

        final int nextPower =
            1 << (32 - Integer.numberOfLeadingZeros(length - 1));
        final int chunk = nextPower <= 256 ? 32 : nextPower / 8;
        return chunk * ((length - 1) / chunk + 1);
    }

    private static byte[] pad(String plaintext) {
        byte[] unpadded = plaintext.getBytes(StandardCharsets.UTF_8);
        int unpaddedLen = unpadded.length;
        int paddedLen = calcPaddedLength(unpaddedLen);
        return concatBytes(
            (unpaddedLen >> 8) & 0xFF,
            unpaddedLen & 0xFF,
            unpadded,
            null,
            null,
            paddedLen + 2
        );
    }

    public static String encrypt(
        String plaintext,
        byte[] conversationKey,
        byte[] nonce
    ) throws Exception {
        if (
            conversationKey == null ||
            conversationKey.length != CONVERSATION_KEY_SIZE
        ) throw new IllegalArgumentException(
            "Conversation key must be 32 bytes"
        );
        nonce = safeNonce(nonce);

        byte[][] keys = getMessageKeys(conversationKey, nonce);
        byte[] chachaKey = keys[0];
        byte[] chachaNonce = keys[1];
        byte[] hmacKey = keys[2];

        byte[] padded = pad(plaintext);
        byte[] ciphertext = NostrUtils
            .getPlatform()
            .chacha20(chachaKey, chachaNonce, padded, true);
        byte[] mac = NostrUtils.getPlatform().hmac(hmacKey, nonce, ciphertext);
        byte[] out = concatBytes(
            VERSION_V2,
            Integer.MIN_VALUE,
            nonce,
            ciphertext,
            mac,
            -1
        );

        return NostrUtils.getPlatform().base64encode(out);
    }

    public static String encrypt(String plaintext, byte[] conversationKey)
        throws Exception {
        return encrypt(plaintext, conversationKey, null);
    }

    private static byte[][] decodePayload(String payload) throws Exception {
        int plen = payload.length();
        if (plen < 132 || plen > 87472) throw new IllegalArgumentException(
            "invalid payload length: " + plen
        );
        if (payload.charAt(0) == '#') throw new IllegalArgumentException(
            "unknown encryption version"
        );

        byte[] data = NostrUtils.getPlatform().base64decode(payload);
        int dataLen = data.length;
        if (
            dataLen < (VERSION_SIZE + NONCE_SIZE + 1 + MAC_SIZE) ||
            dataLen > 65603
        ) {
            throw new IllegalArgumentException(
                "invalid data length: " + dataLen
            );
        }
        if (data[0] != VERSION_V2) {
            throw new IllegalArgumentException(
                "unknown encryption version " + data[0]
            );
        }

        byte[] nonce = Arrays.copyOfRange(
            data,
            VERSION_SIZE,
            VERSION_SIZE + NONCE_SIZE
        );
        byte[] ciphertext = Arrays.copyOfRange(
            data,
            VERSION_SIZE + NONCE_SIZE,
            dataLen - MAC_SIZE
        );
        byte[] mac = Arrays.copyOfRange(data, dataLen - MAC_SIZE, dataLen);

        return new byte[][] { nonce, ciphertext, mac };
    }

    public static String decrypt(String payload, byte[] conversationKey)
        throws Exception {
        if (
            conversationKey == null ||
            conversationKey.length != CONVERSATION_KEY_SIZE
        ) throw new IllegalArgumentException(
            "Conversation key must be 32 bytes"
        );

        byte[][] decodedPayload = decodePayload(payload);
        byte[] nonce = decodedPayload[0];
        byte[] ciphertext = decodedPayload[1];
        byte[] mac = decodedPayload[2];

        byte[][] keys = getMessageKeys(conversationKey, nonce);
        byte[] chachaKey = keys[0];
        byte[] chachaNonce = keys[1];
        byte[] hmacKey = keys[2];

        byte[] calculatedMac = NostrUtils
            .getPlatform()
            .hmac(hmacKey, nonce, ciphertext);
        if (!constantTimeEquals(calculatedMac, mac)) {
            throw new SecurityException(
                "invalid MAC - message authentication failed"
            );
        }

        byte[] padded = NostrUtils
            .getPlatform()
            .chacha20(chachaKey, chachaNonce, ciphertext, false);
        if (padded.length < 3) {
            throw new IllegalArgumentException("invalid padding");
        }

        int unpaddedLen = (padded[0] & 0xFF) << 8 | (padded[1] & 0xFF);
        byte unpadded[] = Arrays.copyOfRange(padded, 2, unpaddedLen + 2);

        if (
            unpaddedLen < MIN_PLAINTEXT_SIZE ||
            unpaddedLen > MAX_PLAINTEXT_SIZE ||
            unpadded.length != unpaddedLen ||
            padded.length != 2 + calcPaddedLength(unpaddedLen)
        ) {
            throw new IllegalArgumentException("invalid padding");
        }

        return new String(unpadded, StandardCharsets.UTF_8);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static byte[] concatBytes(
        int bb,
        int cc,
        byte[] a,
        byte[] b,
        byte[] c,
        int len
    ) {
        int l = 0;
        if (bb != Integer.MIN_VALUE) {
            l++;
        }
        if (cc != Integer.MIN_VALUE) {
            l++;
        }
        if (a != null) {
            l += a.length;
        }
        if (b != null) {
            l += b.length;
        }
        if (c != null) {
            l += c.length;
        }

        byte out[] = new byte[len < l ? l : len];
        int i = 0;
        if (bb != Integer.MIN_VALUE) {
            out[i] = (byte) bb;
            i++;
        }
        if (cc != Integer.MIN_VALUE) {
            out[i] = (byte) cc;
            i++;
        }
        if (a != null) {
            System.arraycopy(a, 0, out, i, a.length);
            i += a.length;
        }
        if (b != null) {
            System.arraycopy(b, 0, out, i, b.length);
            i += b.length;
        }
        if (c != null) {
            System.arraycopy(c, 0, out, i, c.length);
            i += c.length;
        }
        assert i == l;
        return out;
    }
}
