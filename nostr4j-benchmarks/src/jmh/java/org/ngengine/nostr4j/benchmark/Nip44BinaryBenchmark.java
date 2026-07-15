/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * See the LICENSE file in the project root for the full license text.
 */
package org.ngengine.nostr4j.benchmark;

import java.util.concurrent.TimeUnit;
import org.ngengine.nostr4j.nip44.Nip44;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class Nip44BinaryBenchmark {

    @State(Scope.Thread)
    public static class CryptoState {

        @Param({ "64", "512", "4096", "16384" })
        public int payloadBytes;

        private byte[] plaintext;
        private byte[] conversationKey;
        private byte[] nonce;
        private byte[] encrypted;

        @Setup(Level.Trial)
        public void setUp() {
            plaintext = new byte[payloadBytes];
            conversationKey = new byte[32];
            nonce = new byte[32];
            for (int i = 0; i < plaintext.length; i++) {
                plaintext[i] = (byte) (i * 31);
            }
            for (int i = 0; i < 32; i++) {
                conversationKey[i] = (byte) (i + 1);
                nonce[i] = (byte) (32 - i);
            }
            encrypted = Nip44.encryptSyncBinary(plaintext, conversationKey, nonce);
        }
    }

    @Benchmark
    public byte[] encrypt(CryptoState state) {
        return Nip44.encryptSyncBinary(state.plaintext, state.conversationKey, state.nonce);
    }

    @Benchmark
    public byte[] decrypt(CryptoState state) {
        return Nip44.decryptSyncBinary(state.encrypted, state.conversationKey);
    }
}
