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
package org.ngengine.nostr4j;

import com.samourai.wallet.segwit.Bech32Util;
import com.samourai.wallet.segwit.Pair;
import org.ngengine.nostr4j.utils.Bech32;

public class TestBech32Performance {

    private static final String[] VALID = {
        "A12UEL5L",
        "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
        "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
        "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j",
        "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
        "nsec1v4gj83ph04flwe940mkkr9fnxv0s7r85pqjj3kwuhdg8455f460q08upxx",
        "npub1wpuq4mcuhnxhnrqk85hk29qjz6u93vpzxqy9qpuugpyc302fepkqg8t3a4",
    };

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 100_000;

    public static void main(String[] args) throws Exception {
        System.out.println("Warming up...");

        // Warm-up phase
        runBenchmark(WARMUP_ITERATIONS, false);

        System.out.println("Running benchmark...");
        runBenchmark(BENCHMARK_ITERATIONS, true);
    }

    private static void runBenchmark(int iterations, boolean printResults)
        throws Exception {
        long bech32UtilEncodeTime = 0;
        long bech32UtilDecodeTime = 0;

        long bech32RefactoredEncodeTime = 0;
        long bech32RefactoredDecodeTime = 0;

        for (int i = 0; i < iterations; i++) {
            for (String s : VALID) {
                byte[] hrp = s.substring(0, s.lastIndexOf('1')).getBytes();

                // --------- Original Bech32Util ---------
                long startDecodeUtil = System.nanoTime();
                Pair<byte[], byte[]> decodedUtil = Bech32Util
                    .getInstance()
                    .bech32Decode(s);
                long endDecodeUtil = System.nanoTime();
                bech32UtilDecodeTime += (endDecodeUtil - startDecodeUtil);

                long startEncodeUtil = System.nanoTime();
                Bech32Util
                    .getInstance()
                    .bech32Encode(hrp, decodedUtil.getRight());
                long endEncodeUtil = System.nanoTime();
                bech32UtilEncodeTime += (endEncodeUtil - startEncodeUtil);

                // --------- Refactored Bech32 ---------
                long startDecodeRefactored = System.nanoTime();
                java.nio.ByteBuffer decodedRefactored = Bech32.bech32Decode(s);
                long endDecodeRefactored = System.nanoTime();
                bech32RefactoredDecodeTime +=
                    (endDecodeRefactored - startDecodeRefactored);

                long startEncodeRefactored = System.nanoTime();
                Bech32.bech32Encode(hrp, decodedRefactored, new byte[6]);
                long endEncodeRefactored = System.nanoTime();
                bech32RefactoredEncodeTime +=
                    (endEncodeRefactored - startEncodeRefactored);
            }
        }

        if (printResults) {
            int totalOps = iterations * VALID.length;

            System.out.println(
                "---- Results over " + totalOps + " operations ----"
            );

            System.out.println(
                "Bech32Util (Original) - Decode total time: " +
                bech32UtilDecodeTime /
                1_000_000.0 +
                " ms"
            );
            System.out.println(
                "Bech32Util (Original) - Encode total time: " +
                bech32UtilEncodeTime /
                1_000_000.0 +
                " ms"
            );

            System.out.println(
                "Bech32 (Refactored)   - Decode total time: " +
                bech32RefactoredDecodeTime /
                1_000_000.0 +
                " ms"
            );
            System.out.println(
                "Bech32 (Refactored)   - Encode total time: " +
                bech32RefactoredEncodeTime /
                1_000_000.0 +
                " ms"
            );

            System.out.println();
            System.out.println(
                "Bech32Util (Original) - Avg Decode time per op: " +
                bech32UtilDecodeTime /
                (double) totalOps +
                " ns"
            );
            System.out.println(
                "Bech32Util (Original) - Avg Encode time per op: " +
                bech32UtilEncodeTime /
                (double) totalOps +
                " ns"
            );

            System.out.println(
                "Bech32 (Refactored)   - Avg Decode time per op: " +
                bech32RefactoredDecodeTime /
                (double) totalOps +
                " ns"
            );
            System.out.println(
                "Bech32 (Refactored)   - Avg Encode time per op: " +
                bech32RefactoredEncodeTime /
                (double) totalOps +
                " ns"
            );
        }
    }
}
