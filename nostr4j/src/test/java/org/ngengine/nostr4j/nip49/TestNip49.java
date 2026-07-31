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

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.ngengine.bech32.Bech32;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;

public class TestNip49 {

    private static final String OFFICIAL_PRIVATE_KEY = "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683";
    private static final String OFFICIAL_NCRYPTSEC =
        "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p";

    @Test
    public void encryptDecrypt() throws Nip49FailedException, Exception {
        NostrPrivateKey privateKey = NostrPrivateKey.fromHex(OFFICIAL_PRIVATE_KEY);
        String encrypted = Nip49.encrypt(privateKey, "ÅΩẛ̣").await();
        NostrPrivateKey decrypted = Nip49.decrypt(encrypted, "ÅΩṩ").await();

        assertEquals(privateKey, decrypted);
    }

    @Test
    public void decryptOfficialVector() throws Exception {
        NostrPrivateKey decrypted = Nip49.decrypt(OFFICIAL_NCRYPTSEC, "nostr").await();
        assertEquals(OFFICIAL_PRIVATE_KEY, decrypted.asHex());
    }

    @Test(expected = Nip49FailedException.class)
    public void badDecrypt() throws Nip49FailedException, Exception {
        Nip49.decrypt(OFFICIAL_NCRYPTSEC, "bad", 1024 * 1024 * 128).await();
    }

    @Test
    public void testMemoryRequirement() {
        // logn | memory MB
        long vv[][] = { { 16, 64 }, { 18, 256 }, { 20, 1024 } };
        for (int i = 0; i < vv.length; i++) {
            int logn = (int) vv[i][0];
            long memoryMB = vv[i][1];
            long memoryBytes = memoryMB * 1024 * 1024;
            long memoryRequirement = Nip49.getApproximatedMemoryRequirement(logn);
            System.out.println(
                "logn: " +
                logn +
                " memoryMB: " +
                memoryBytes /
                1024 /
                1024 +
                " MB  memoryRequirement: " +
                memoryRequirement /
                1024 /
                1024 +
                " MB"
            );
            assertEquals(memoryRequirement, memoryBytes);
        }
    }

    @Test
    public void rejectsUnsupportedLognDuringEncryption() throws Exception {
        NostrPrivateKey privateKey = NostrPrivateKey.fromHex(
            "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"
        );

        for (int logn : new int[] { 15, 21, 31, 32, 33, 63, 255 }) {
            Nip49FailedException failure = assertThrows(
                Nip49FailedException.class,
                () -> Nip49.encryptSync(privateKey, "password", logn, Integer.MAX_VALUE)
            );
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals("Unsupported NIP-49 logn: " + logn, failure.getCause().getMessage());
        }
    }

    @Test
    public void rejectsUnsupportedLognDuringDecryption() throws Exception {
        for (int logn : new int[] { 31, 32, 33, 63, 255 }) {
            ByteBuffer decoded = Bech32.bech32Decode(OFFICIAL_NCRYPTSEC);
            byte[] payload = new byte[decoded.remaining()];
            decoded.get(payload);
            payload[1] = (byte) logn;
            String altered = Bech32.bech32Encode("ncryptsec".getBytes(StandardCharsets.UTF_8), ByteBuffer.wrap(payload));

            Nip49FailedException failure = assertThrows(
                Nip49FailedException.class,
                () -> Nip49.decryptSync(altered, "nostr", Integer.MAX_VALUE)
            );
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals("Unsupported NIP-49 logn: " + logn, failure.getCause().getMessage());
        }
    }
}
