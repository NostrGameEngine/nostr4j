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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.nip44.TestNip44;

public class TestNip49 {

    private static JsonArray testVectors;

    @BeforeClass
    public static void loadTestVectors() throws Exception {
        InputStream is = TestNip44.class.getResourceAsStream("/org/ngengine/nostr/nip49/nip49-vectors.json");
        InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
        testVectors = new Gson().fromJson(reader, JsonArray.class);
        reader.close();
        is.close();
    }

    @Test
    public void encryptDecrypt() throws Nip49FailedException, Exception {
        for (int i = 0; i < testVectors.size(); i++) {
            JsonArray testVector = testVectors.get(i).getAsJsonArray();
            String password = testVector.get(0).getAsString();
            String secretHex = testVector.get(1).getAsString();
            int logn = testVector.get(2).getAsInt();
            int ksb = testVector.get(3).getAsInt();
            String ncryptsec = testVector.get(4).getAsString();
            NostrPrivateKey sec = NostrPrivateKey.fromHex(secretHex);
            sec.setKeySecurity(NostrPrivateKey.KeySecurity.values()[ksb]);
            String there = Nip49.encrypt(sec, password, logn, 1024 * 1024 * 128).await();
            NostrPrivateKey back = Nip49.decrypt(there, password, 1024 * 1024 * 128).await();
            NostrPrivateKey again = Nip49.decrypt(ncryptsec, password, 1024 * 1024 * 128).await();
            NostrPrivateKey again2 = NostrPrivateKey.fromNcryptsec(ncryptsec, password).await();
            String there2 = again2.asNcryptsec(password).await();
            NostrPrivateKey back2 = NostrPrivateKey.fromNcryptsec(there2, password).await();

            assertEquals(sec, back);
            assertEquals(sec, again);
            assertEquals(sec.getKeySecurity().ordinal(), ksb);
            assertEquals(again, again2);
            assertEquals(again2, back2);
        }
    }

    @Test(expected = ExecutionException.class)
    public void badDecrypt() throws Nip49FailedException,  Exception {
        int i = 0;
        JsonArray testVector = testVectors.get(i).getAsJsonArray();
        String ncryptsec = testVector.get(4).getAsString();
        Nip49.decrypt(ncryptsec, "bad", 1024 * 1024 * 128).await();
    }

    @Test
    public void testMemoryRequirement() {
        // logn | memory MB
        long vv[][] = {
            { 16, 64 },
            { 18, 256 },
            { 20, 1024 },
            { 21, 2048 },
            { 22, 4096 },
            { 23, 8192 },
            { 24, 16384 },
            { 25, 32768 },
            { 26, 65536 },
            { 27, 131072 },
            { 28, 262144 },
            { 29, 524288 },
            { 30, 1048576 },
        };
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
}
