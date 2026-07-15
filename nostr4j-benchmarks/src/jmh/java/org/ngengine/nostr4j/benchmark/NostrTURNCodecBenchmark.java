/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * See the LICENSE file in the project root for the full license text.
 */
package org.ngengine.nostr4j.benchmark;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.ngengine.nostr4j.rtc.turn.NostrTURNCodec;
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
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class NostrTURNCodecBenchmark {

    @State(Scope.Thread)
    public static class CodecState {

        @Param({ "64", "512", "4096", "16384" })
        public int payloadBytes;

        private byte[] header;
        private byte[] payload;
        private List<byte[]> payloads;
        private ByteBuffer encodedFrame;

        @Setup(Level.Trial)
        public void setUp() {
            header = "{\"kind\":25051,\"content\":\"benchmark\"}".getBytes(StandardCharsets.UTF_8);
            payload = new byte[payloadBytes];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) i;
            }
            payloads = Collections.singletonList(payload);
            encodedFrame = NostrTURNCodec.encodeFrame(header, 42L, 7, payloads);
        }
    }

    @Benchmark
    public List<byte[]> decodePayloads(CodecState state) {
        List<byte[]> decoded = new ArrayList<byte[]>(1);
        NostrTURNCodec.decodePayloads(state.encodedFrame, decoded);
        return decoded;
    }

    @Benchmark
    public List<byte[]> encodeAndDecode(CodecState state) {
        ByteBuffer frame = NostrTURNCodec.encodeFrame(state.header, 42L, 7, state.payloads);
        List<byte[]> decoded = new ArrayList<byte[]>(1);
        NostrTURNCodec.decodePayloads(frame, decoded);
        return decoded;
    }
}
