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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

public class TestNip04 {

    @Test
    public void testEncryptAndDecryptMessage() throws Exception {
        NostrPrivateKey sk1 = NostrPrivateKey.generate();
        NostrPrivateKey sk2 = NostrPrivateKey.generate();
        NostrPublicKey pk1 = sk1.getPublicKey();
        NostrPublicKey pk2 = sk2.getPublicKey();
        String message = "hello";
        String ciphertext = Nip04.encryptSync(message, sk1, pk2);
        String plaintext = Nip04.decryptSync(ciphertext, sk2, pk1);
        assertEquals(message, plaintext);
    }

    @Test
    public void testDecryptMessageFromGoNostr() throws Exception {
        // Use the hardcoded private keys from the original test
        NostrPrivateKey sk1 = NostrPrivateKey.fromHex("91ba716fa9e7ea2fcbad360cf4f8e0d312f73984da63d90f524ad61a6a1e7dbe");
        NostrPrivateKey sk2 = NostrPrivateKey.fromHex("96f6fa197aa07477ab88f6981118466ae3a982faab8ad5db9d5426870c73d220");

        // Derive public key from sk1
        NostrPublicKey pk1 = sk1.getPublicKey();

        // Hardcoded ciphertext from the original test
        String ciphertext = "zJxfaJ32rN5Dg1ODjOlEew==?iv=EV5bUjcc4OX2Km/zPp4ndQ==";

        // Decrypt message with sk2 from pk1
        String plaintext = Nip04.decryptSync(ciphertext, sk2, pk1);

        assertEquals("nanana", plaintext);
    }

    @Test
    public void testDecryptBigPayloadFromGoNostr() throws Exception {
        // Use the hardcoded private keys from the original test
        NostrPrivateKey sk1 = NostrPrivateKey.fromHex("91ba716fa9e7ea2fcbad360cf4f8e0d312f73984da63d90f524ad61a6a1e7dbe");
        NostrPrivateKey sk2 = NostrPrivateKey.fromHex("96f6fa197aa07477ab88f6981118466ae3a982faab8ad5db9d5426870c73d220");

        // Derive public key from sk1
        NostrPublicKey pk1 = sk1.getPublicKey();

        // Hardcoded ciphertext from the original test
        String ciphertext =
            "6f8dMstm+udOu7yipSn33orTmwQpWbtfuY95NH+eTU1kArysWJIDkYgI2D25EAGIDJsNd45jOJ2NbVOhFiL3ZP/NWsTwXokk34iyHyA/lkjzugQ1bHXoMD1fP/Ay4hB4al1NHb8HXHKZaxPrErwdRDb8qa/I6dXb/1xxyVvNQBHHvmsM5yIFaPwnCN1DZqXf2KbTA/Ekz7Hy+7R+Sy3TXLQDFpWYqykppkXc7Fs0qSuPRyxz5+anuN0dxZa9GTwTEnBrZPbthKkNRrvZMdTGJ6WumOh9aUq8OJJWy9aOgsXvs7qjN1UqcCqQqYaVnEOhCaqWNDsVtsFrVDj+SaLIBvCiomwF4C4nIgngJ5I69tx0UNI0q+ZnvOGQZ7m1PpW2NYP7Yw43HJNdeUEQAmdCPnh/PJwzLTnIxHmQU7n7SPlMdV0SFa6H8y2HHvex697GAkyE5t8c2uO24OnqIwF1tR3blIqXzTSRl0GA6QvrSj2p4UtnWjvF7xT7RiIEyTtgU/AsihTrXyXzWWZaIBJogpgw6erlZqWjCH7sZy/WoGYEiblobOAqMYxax6vRbeuGtoYksr/myX+x9rfLrYuoDRTw4woXOLmMrrj+Mf0TbAgc3SjdkqdsPU1553rlSqIEZXuFgoWmxvVQDtekgTYyS97G81TDSK9nTJT5ilku8NVq2LgtBXGwsNIw/xekcOUzJke3kpnFPutNaexR1VF3ohIuqRKYRGcd8ADJP2lfwMcaGRiplAmFoaVS1YUhQwYFNq9rMLf7YauRGV4BJg/t9srdGxf5RoKCvRo+XM/nLxxysTR9MVaEP/3lDqjwChMxs+eWfLHE5vRWV8hUEqdrWNZV29gsx5nQpzJ4PARGZVu310pQzc6JAlc2XAhhFk6RamkYJnmCSMnb/RblzIATBi2kNrCVAlaXIon188inB62rEpZGPkRIP7PUfu27S/elLQHBHeGDsxOXsBRo1gl3te+raoBHsxo6zvRnYbwdAQa5taDE63eh+fT6kFI+xYmXNAQkU8Dp0MVhEh4JQI06Ni/AKrvYpC95TXXIphZcF+/Pv/vaGkhG2X9S3uhugwWK?iv=2vWkOQQi0WynNJz/aZ4k2g==";

        // Create expected plaintext (800 'z' characters)
        StringBuilder expectedPlaintext = new StringBuilder();
        for (int i = 0; i < 800; i++) {
            expectedPlaintext.append('z');
        }

        // Decrypt message with sk2 from pk1
        String plaintext = Nip04.decryptSync(ciphertext, sk2, pk1);

        assertEquals(expectedPlaintext.toString(), plaintext);
    }

    @Test
    public void testRoundTripWithLargeData() throws Exception {
        // Generate two key pairs
        NostrPrivateKey sk1 = NostrPrivateKey.generate();
        NostrPrivateKey sk2 = NostrPrivateKey.generate();

        NostrPublicKey pk1 = sk1.getPublicKey();
        NostrPublicKey pk2 = sk2.getPublicKey();

        // Create a large message (1000 characters)
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            message.append((char) ('a' + (i % 26)));
        }

        // Encrypt and decrypt
        String ciphertext = Nip04.encryptSync(message.toString(), sk1, pk2);

        String plaintext = Nip04.decryptSync(ciphertext, sk2, pk1);

        assertEquals(message.toString(), plaintext);
    }

    @Test
    public void testEncryptWithDifferentKeys() throws Exception {
        // Generate three key pairs
        NostrPrivateKey sk1 = NostrPrivateKey.generate();
        NostrPrivateKey sk2 = NostrPrivateKey.generate();
        NostrPrivateKey sk3 = NostrPrivateKey.generate();

        NostrPublicKey pk1 = sk1.getPublicKey();
        NostrPublicKey pk2 = sk2.getPublicKey();
        NostrPublicKey pk3 = sk3.getPublicKey();

        // Encrypt message from sk1 to pk2
        String message = "secret message";
        String ciphertext = Nip04.encryptSync(message, sk1, pk2);

        try {
            // Trying to decrypt with wrong key (sk3) should not produce the correct message
            String wrongPlaintext = Nip04.decryptSync(ciphertext, sk3, pk1);

            // The wrong decryption should not match the original message
            boolean decryptionFailed = !message.equals(wrongPlaintext);
            assertEquals(true, decryptionFailed);
        } catch (Exception e) {
            assertTrue(true);
        }

        // But decrypting with the correct key works
        String correctPlaintext = Nip04.decryptSync(ciphertext, sk2, pk1);

        assertEquals(message, correctPlaintext);
    }
}
