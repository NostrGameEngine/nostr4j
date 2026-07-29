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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

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
    private static final byte[] NIP44_V2_BYTES = "nip44-v2".getBytes(StandardCharsets.UTF_8);
    private static final AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor(Nip44.class);

    public static byte[] getConversationKeySync(NostrPrivateKey privateKey, NostrPublicKey publicKey) {
        return toByteArray(getConversationKeyBufferSync(privateKey, publicKey));
    }

    public static ByteBuffer getConversationKeyBufferSync(NostrPrivateKey privateKey, NostrPublicKey publicKey) {
        ByteBuffer xOnlyPublicKey = publicKey.asReadOnlyBuffer();
        ByteBuffer publicKey33 = allocate(xOnlyPublicKey.remaining() + 1);
        publicKey33.put((byte) 0x02);
        publicKey33.put(xOnlyPublicKey);
        publicKey33.flip();

        ByteBuffer shared = NGEUtils.getPlatform().secp256k1SharedSecret(privateKey.asReadOnlyBuffer(), publicKey33);
        ByteBuffer sharedX = range(shared, 1, CONVERSATION_KEY_SIZE);
        return NGEUtils.getPlatform().hkdf_extract(ByteBuffer.wrap(NIP44_V2_BYTES), sharedX);
    }

    private static ByteBuffer safeNonce(ByteBuffer nonce) {
        if (nonce == null) {
            nonce = NGEUtils.getPlatform().randomBytesBuffer(NONCE_SIZE);
        } else if (nonce.remaining() != NONCE_SIZE) {
            throw new IllegalArgumentException("Nonce must be 32 bytes");
        }
        return nonce.slice();
    }

    private static MessageKeys getMessageKeys(ByteBuffer conversationKey, ByteBuffer nonce) {
        requireLength(conversationKey, CONVERSATION_KEY_SIZE, "Conversation key");
        nonce = safeNonce(nonce);
        ByteBuffer keys = NGEUtils.getPlatform().hkdf_expand(conversationKey, nonce, 76);
        return new MessageKeys(range(keys, 0, 32), range(keys, 32, 12), range(keys, 44, 32));
    }

    private static int calcPaddedLength(int length) {
        if (length < 1) throw new IllegalArgumentException("Expected positive integer");

        if (length <= 32) return 32;

        final int nextPower = 1 << (32 - Integer.numberOfLeadingZeros(length - 1));
        final int chunk = nextPower <= 256 ? 32 : nextPower / 8;
        return chunk * ((length - 1) / chunk + 1);
    }

    private static ByteBuffer pad(ByteBuffer unpadded) {
        if (unpadded == null) {
            throw new IllegalArgumentException("NIP44 plaintext must be between 1 and 65535 bytes");
        }
        ByteBuffer input = unpadded.slice();
        int unpaddedLen = input.remaining();
        if (unpaddedLen > MAX_PLAINTEXT_SIZE) {
            throw new IllegalArgumentException(
                "NIP44 plaintext too large: " + unpaddedLen + " bytes, maximum supported is 65535"
            );
        }
        int paddedLen = calcPaddedLength(unpaddedLen);
        ByteBuffer output = allocate(paddedLen + 2);
        output.put((byte) (unpaddedLen >> 8));
        output.put((byte) unpaddedLen);
        output.put(input);
        while (output.hasRemaining()) {
            output.put((byte) 0);
        }
        output.flip();
        return output;
    }

    public static byte[] encryptSyncBinary(byte[] data, byte[] conversationKey, byte[] nonce) {
        if (data == null) {
            throw new IllegalArgumentException("NIP44 plaintext must be between 1 and 65535 bytes");
        }
        if (conversationKey == null) {
            throw new IllegalArgumentException("Conversation key must be 32 bytes");
        }
        ByteBuffer nonceBuffer = nonce == null ? null : ByteBuffer.wrap(nonce);
        return toByteArray(encryptSyncBinary(ByteBuffer.wrap(data), ByteBuffer.wrap(conversationKey), nonceBuffer));
    }

    public static ByteBuffer encryptSyncBinary(ByteBuffer data, ByteBuffer conversationKey, ByteBuffer nonce) {
        if (data == null || data.remaining() < MIN_PLAINTEXT_SIZE) {
            throw new IllegalArgumentException("NIP44 plaintext must be between 1 and 65535 bytes");
        }
        if (data.remaining() > MAX_PLAINTEXT_SIZE) {
            throw new IllegalArgumentException(
                "NIP44 plaintext too large: " + data.remaining() + " bytes, maximum supported is 65535"
            );
        }
        requireLength(conversationKey, CONVERSATION_KEY_SIZE, "Conversation key");
        nonce = safeNonce(nonce);

        MessageKeys keys = getMessageKeys(conversationKey, nonce);
        ByteBuffer padded = pad(data);
        ByteBuffer ciphertext = NGEUtils.getPlatform().chacha20(keys.chachaKey, keys.chachaNonce, padded, true);
        ByteBuffer mac = NGEUtils.getPlatform().hmac(keys.hmacKey, nonce, ciphertext);
        ByteBuffer output = allocate(VERSION_SIZE + NONCE_SIZE + ciphertext.remaining() + MAC_SIZE);
        output.put(VERSION_V2);
        output.put(nonce.slice());
        output.put(ciphertext.slice());
        output.put(mac.slice());
        output.flip();
        return output;
    }

    public static byte[] encryptSyncBinary(byte[] data, byte[] conversationKey) {
        return encryptSyncBinary(data, conversationKey, null);
    }

    public static String encryptSync(String plaintext, byte[] conversationKey, byte[] nonce) {
        if (conversationKey == null) {
            throw new IllegalArgumentException("Conversation key must be 32 bytes");
        }
        return encryptSync(plaintext, ByteBuffer.wrap(conversationKey), nonce == null ? null : ByteBuffer.wrap(nonce));
    }

    public static String encryptSync(String plaintext, ByteBuffer conversationKey, ByteBuffer nonce) {
        ByteBuffer encrypted = encryptSyncBinary(
            ByteBuffer.wrap(plaintext.getBytes(StandardCharsets.UTF_8)),
            conversationKey,
            nonce
        );
        return NGEUtils.getPlatform().base64encode(encrypted);
    }

    public static String encryptSync(String plaintext, byte[] conversationKey) {
        return encryptSync(plaintext, conversationKey, null);
    }

    private static DecodedPayload decodePayload(ByteBuffer data) {
        ByteBuffer input = data.slice();
        int dataLen = input.remaining();
        if (dataLen < (VERSION_SIZE + NONCE_SIZE + 1 + MAC_SIZE) || dataLen > 65603) {
            throw new IllegalArgumentException("invalid data length: " + dataLen);
        }
        if (input.get(0) != VERSION_V2) {
            throw new IllegalArgumentException("unknown encryption version " + input.get(0));
        }

        ByteBuffer nonce = range(input, VERSION_SIZE, NONCE_SIZE);
        ByteBuffer ciphertext = range(input, VERSION_SIZE + NONCE_SIZE, dataLen - VERSION_SIZE - NONCE_SIZE - MAC_SIZE);
        ByteBuffer mac = range(input, dataLen - MAC_SIZE, MAC_SIZE);
        return new DecodedPayload(nonce, ciphertext, mac);
    }

    public static byte[] decryptSyncBinary(byte[] payloadData, byte[] conversationKey) {
        return toByteArray(decryptSyncBinary(ByteBuffer.wrap(payloadData), ByteBuffer.wrap(conversationKey)));
    }

    public static ByteBuffer decryptSyncBinary(ByteBuffer payloadData, ByteBuffer conversationKey) {
        requireLength(conversationKey, CONVERSATION_KEY_SIZE, "Conversation key");

        DecodedPayload decodedPayload = decodePayload(payloadData);
        MessageKeys keys = getMessageKeys(conversationKey, decodedPayload.nonce);
        ByteBuffer calculatedMac = NGEUtils.getPlatform().hmac(keys.hmacKey, decodedPayload.nonce, decodedPayload.ciphertext);
        if (!constantTimeEquals(calculatedMac, decodedPayload.mac)) {
            throw new SecurityException("invalid MAC - message authentication failed");
        }

        ByteBuffer padded = NGEUtils.getPlatform().chacha20(keys.chachaKey, keys.chachaNonce, decodedPayload.ciphertext, false);
        if (padded.remaining() < 3) {
            throw new IllegalArgumentException("invalid padding");
        }

        int unpaddedLen = (padded.get(0) & 0xff) << 8 | (padded.get(1) & 0xff);
        if (unpaddedLen < MIN_PLAINTEXT_SIZE || unpaddedLen > MAX_PLAINTEXT_SIZE || unpaddedLen + 2 > padded.remaining()) {
            throw new IllegalArgumentException("invalid padding");
        }
        ByteBuffer unpadded = range(padded, 2, unpaddedLen);

        if (unpadded.remaining() != unpaddedLen || padded.remaining() != 2 + calcPaddedLength(unpaddedLen)) {
            throw new IllegalArgumentException("invalid padding");
        }

        return unpadded;
    }

    public static String decryptSync(String payload, byte[] conversationKey) {
        if (conversationKey == null || conversationKey.length != CONVERSATION_KEY_SIZE) throw new IllegalArgumentException(
            "Conversation key must be 32 bytes"
        );
        return decryptSync(payload, ByteBuffer.wrap(conversationKey));
    }

    public static String decryptSync(String payload, ByteBuffer conversationKey) {
        requireLength(conversationKey, CONVERSATION_KEY_SIZE, "Conversation key");

        int plen = payload.length();
        if (plen < 132 || plen > 87472) throw new IllegalArgumentException("invalid payload length: " + plen);
        if (payload.charAt(0) == '#') throw new IllegalArgumentException("unknown encryption version");

        ByteBuffer payloadData = NGEUtils.getPlatform().base64decodeBuffer(payload);
        ByteBuffer decrypted = decryptSyncBinary(payloadData, conversationKey);
        return new String(toByteArray(decrypted), StandardCharsets.UTF_8);
    }

    private static boolean constantTimeEquals(ByteBuffer a, ByteBuffer b) {
        if (a.remaining() != b.remaining()) return false;

        int result = 0;
        for (int i = 0; i < a.remaining(); i++) {
            result |= a.get(a.position() + i) ^ b.get(b.position() + i);
        }
        return result == 0;
    }

    private static ByteBuffer allocate(int size) {
        return NGEUtils.getPlatform().getNativeAllocator().malloc(size);
    }

    private static ByteBuffer range(ByteBuffer source, int offset, int length) {
        ByteBuffer view = source.slice();
        if (offset < 0 || length < 0 || offset + length > view.remaining()) {
            throw new IllegalArgumentException("Invalid buffer range");
        }
        view.position(offset);
        view.limit(offset + length);
        return view.slice();
    }

    private static byte[] toByteArray(ByteBuffer source) {
        ByteBuffer view = source.slice();
        byte[] output = new byte[view.remaining()];
        view.get(output);
        return output;
    }

    private static void requireLength(ByteBuffer buffer, int expected, String name) {
        if (buffer == null || buffer.remaining() != expected) {
            throw new IllegalArgumentException(name + " must be " + expected + " bytes");
        }
    }

    private static final class MessageKeys {

        final ByteBuffer chachaKey;
        final ByteBuffer chachaNonce;
        final ByteBuffer hmacKey;

        MessageKeys(ByteBuffer chachaKey, ByteBuffer chachaNonce, ByteBuffer hmacKey) {
            this.chachaKey = chachaKey;
            this.chachaNonce = chachaNonce;
            this.hmacKey = hmacKey;
        }
    }

    private static final class DecodedPayload {

        final ByteBuffer nonce;
        final ByteBuffer ciphertext;
        final ByteBuffer mac;

        DecodedPayload(ByteBuffer nonce, ByteBuffer ciphertext, ByteBuffer mac) {
            this.nonce = nonce;
            this.ciphertext = ciphertext;
            this.mac = mac;
        }
    }

    public static AsyncTask<String> encrypt(String plaintext, byte[] conversationKey, byte[] nonce) {
        return executor.run(() -> {
            return encryptSync(plaintext, conversationKey, nonce);
        });
    }

    public static AsyncTask<String> encrypt(String plaintext, byte[] conversationKey) {
        return executor.run(() -> {
            return encryptSync(plaintext, conversationKey);
        });
    }

    public static AsyncTask<String> decrypt(String payload, byte[] conversationKey) {
        return executor.run(() -> {
            return decryptSync(payload, conversationKey);
        });
    }

    public static AsyncTask<byte[]> getConversationKey(NostrPrivateKey privateKey, NostrPublicKey publicKey) {
        return executor.run(() -> {
            return getConversationKeySync(privateKey, publicKey);
        });
    }

    public static AsyncTask<ByteBuffer> getConversationKeyBuffer(NostrPrivateKey privateKey, NostrPublicKey publicKey) {
        return executor.run(() -> {
            return getConversationKeyBufferSync(privateKey, publicKey);
        });
    }

    public static AsyncTask<byte[]> encryptBinary(byte[] data, byte[] conversationKey, byte[] nonce) {
        return executor.run(() -> {
            return encryptSyncBinary(data, conversationKey, nonce);
        });
    }

    public static AsyncTask<byte[]> encryptBinary(byte[] data, byte[] conversationKey) {
        return executor.run(() -> {
            return encryptSyncBinary(data, conversationKey);
        });
    }

    public static AsyncTask<byte[]> decryptBinary(byte[] payloadData, byte[] conversationKey) {
        return executor.run(() -> {
            return decryptSyncBinary(payloadData, conversationKey);
        });
    }

    public static AsyncTask<ByteBuffer> encryptBinary(ByteBuffer data, ByteBuffer conversationKey, ByteBuffer nonce) {
        ByteBuffer dataView = readOnlyView(data);
        ByteBuffer keyView = readOnlyView(conversationKey);
        ByteBuffer nonceView = nonce == null ? null : readOnlyView(nonce);
        return executor.run(() -> {
            return encryptSyncBinary(dataView, keyView, nonceView);
        });
    }

    public static AsyncTask<ByteBuffer> encryptBinary(ByteBuffer data, ByteBuffer conversationKey) {
        return encryptBinary(data, conversationKey, null);
    }

    public static AsyncTask<ByteBuffer> decryptBinary(ByteBuffer payloadData, ByteBuffer conversationKey) {
        ByteBuffer payloadView = readOnlyView(payloadData);
        ByteBuffer keyView = readOnlyView(conversationKey);
        return executor.run(() -> {
            return decryptSyncBinary(payloadView, keyView);
        });
    }

    private static ByteBuffer readOnlyView(ByteBuffer source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        return source.slice().asReadOnlyBuffer();
    }
}
