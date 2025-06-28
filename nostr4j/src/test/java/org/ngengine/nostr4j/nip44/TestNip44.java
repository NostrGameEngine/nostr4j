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

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip44.Nip44;

/**
 * Ported https://github.com/nbd-wtf/nostr-tools/blob/master/nip44.test.ts
 */
public class TestNip44 {

    private static JsonObject testVectors;

    @BeforeClass
    public static void loadTestVectors() throws Exception {
        // Load test vectors from JSON file
        InputStream is = TestNip44.class.getResourceAsStream("/org/ngengine/nostr/unit/nip44-vectors.json");
        InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
        testVectors = new Gson().fromJson(reader, JsonObject.class).getAsJsonObject("v2");
        reader.close();
        is.close();
    }

    @Test
    public void testGetConversationKeyValid() throws Exception {
        JsonArray vectors = testVectors.getAsJsonObject("valid").getAsJsonArray("get_conversation_key");

        for (JsonElement element : vectors) {
            JsonObject vector = element.getAsJsonObject();
            String sec1Hex = vector.get("sec1").getAsString();
            String pub2Hex = vector.get("pub2").getAsString();
            String expectedKey = vector.get("conversation_key").getAsString();

            // Convert to NostrPrivateKey/PublicKey
            NostrPrivateKey privateKey = NostrPrivateKey.fromHex(sec1Hex);
            NostrPublicKey publicKey = NostrPublicKey.fromHex(pub2Hex);

            // Get conversation key
            byte[] conversationKey = Nip44.getConversationKey(privateKey, publicKey).await();

            // Assert equality with expected key
            assertEquals(
                "Conversation key mismatch for sec1=" + sec1Hex + ", pub2=" + pub2Hex,
                expectedKey,
                bytesToHex(conversationKey)
            );
        }
    }

    @Test
    public void testEncryptDecrypt() throws Exception {
        JsonArray vectors = testVectors.getAsJsonObject("valid").getAsJsonArray("encrypt_decrypt");

        for (JsonElement element : vectors) {
            JsonObject vector = element.getAsJsonObject();
            String sec1Hex = vector.get("sec1").getAsString();
            String sec2Hex = vector.get("sec2").getAsString();
            String expectedConvKey = vector.get("conversation_key").getAsString();
            String nonceHex = vector.get("nonce").getAsString();
            String plaintext = vector.get("plaintext").getAsString();
            String expectedPayload = vector.get("payload").getAsString();

            // Generate public key from sec2
            NostrPrivateKey privKey2 = NostrPrivateKey.fromHex(sec2Hex);
            NostrPublicKey pubKey2 = privKey2.getPublicKey();

            // Generate conversation key
            NostrPrivateKey privKey1 = NostrPrivateKey.fromHex(sec1Hex);
            byte[] conversationKey = Nip44.getConversationKey(privKey1, pubKey2).await();

            // Verify conversation key matches expected
            assertEquals("Conversation key mismatch for test case: " + plaintext, expectedConvKey, bytesToHex(conversationKey));

            // Encrypt with provided nonce
            byte[] nonce = hexToBytes(nonceHex);
            String ciphertext = Nip44.encrypt(plaintext, conversationKey, nonce).await();

            // Verify ciphertext matches expected payload
            assertEquals("Encrypted payload mismatch for plaintext: " + plaintext, expectedPayload, ciphertext);

            // Decrypt and verify
            String decrypted = Nip44.decrypt(ciphertext, conversationKey).await();
            assertEquals("Decrypted text doesn't match original for: " + plaintext, plaintext, decrypted);
        }
    }

    @Test
    public void testCalcPaddedLen() {
        JsonArray vectors = testVectors.getAsJsonObject("valid").getAsJsonArray("calc_padded_len");

        for (JsonElement element : vectors) {
            JsonArray pair = element.getAsJsonArray();
            int inputLen = pair.get(0).getAsInt();
            int expectedPaddedLen = pair.get(1).getAsInt();

            // Access the private method via reflection
            java.lang.reflect.Method calcPaddedLength;
            try {
                calcPaddedLength = Nip44.class.getDeclaredMethod("calcPaddedLength", int.class);
                calcPaddedLength.setAccessible(true);
                int actualPaddedLen = (int) calcPaddedLength.invoke(null, inputLen);
                assertEquals("Padding length mismatch for input length " + inputLen, expectedPaddedLen, actualPaddedLen);
            } catch (Exception e) {
                fail("Failed to access calcPaddedLength method: " + e.getMessage());
            }
        }
    }

    @Test
    public void testDecryptInvalid() {
        JsonArray vectors = testVectors.getAsJsonObject("invalid").getAsJsonArray("decrypt");

        for (JsonElement element : vectors) {
            JsonObject vector = element.getAsJsonObject();
            if (!vector.has("conversation_key")) {
                // Skip test cases without conversation keys
                continue;
            }

            final String convKeyHex = vector.get("conversation_key").getAsString();
            final String payload = vector.get("payload").getAsString();
            final String note = vector.get("note").getAsString();

            final byte[] conversationKey = hexToBytes(convKeyHex);

            try {
                Nip44.decrypt(payload, conversationKey).await();
                fail("Expected exception for invalid payload: " + note);
            } catch (Exception e) {
                // Check that the exception message contains the expected note
                String message = e.getMessage();
                if (message == null && e.getCause() != null) {
                    message = e.getCause().getMessage();
                }

                boolean matchesNote = message != null && message.toLowerCase().contains(note.toLowerCase());

                // The test should pass if either:
                // 1. Note mentions "invalid base64" and we got an IllegalArgumentException
                // 2. Note mentions "invalid MAC" and we got a SecurityException
                // 3. Note mentions "invalid padding" and exception message contains that
                boolean matchesSpecificError =
                    (note.contains("invalid base64") && e instanceof IllegalArgumentException) ||
                    (note.contains("invalid MAC") && e instanceof SecurityException) ||
                    (note.contains("invalid padding") && message != null && message.contains("padding"));

                assertTrue(
                    "Exception message should relate to: " + note + " but was: " + message,
                    matchesNote || matchesSpecificError
                );
            }
        }
    }

    @Test
    public void testGetConversationKeyInvalid() {
        JsonArray vectors = testVectors.getAsJsonObject("invalid").getAsJsonArray("get_conversation_key");

        for (JsonElement element : vectors) {
            JsonObject vector = element.getAsJsonObject();
            final String sec1Hex = vector.get("sec1").getAsString();
            final String pub2Hex = vector.get("pub2").getAsString();

            try {
                NostrPrivateKey privateKey = NostrPrivateKey.fromHex(sec1Hex);
                NostrPublicKey publicKey = NostrPublicKey.fromHex(pub2Hex);
                Nip44.getConversationKey(privateKey, publicKey).await();
                fail("Expected exception for invalid keys: sec1=" + sec1Hex + ", pub2=" + pub2Hex);
            } catch (Exception e) {
                // Expected exception - test passes
            }
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }

        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
