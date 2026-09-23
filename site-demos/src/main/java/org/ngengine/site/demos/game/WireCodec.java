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

package org.ngengine.site.demos.game;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Byte framing for game traffic over NostrRTCRoom channels.
 *
 * <p>Format: {@code [u32 BE length][UTF-8 JSON bytes]}. One frame per
 * {@code send()}/{@code broadcast()} call — Nostr4J delivers DataChannel
 * messages whole, so a single frame per datagram is the natural unit.</p>
 *
 * <p>The game protocol itself (message types, JSON schemas) is documented in
 * {@code PROTOCOL.md}; this class only frames opaque JSON strings.</p>
 *
 * <p>Zero platform/JS dependencies. The same framing is mirrored in
 * {@code site/assets/js/sats-seas.js} (not strictly needed there, since the
 * Java side encodes/decodes, but kept symmetric for tests).</p>
 */
public final class WireCodec {

    private WireCodec() {}

    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
    public static final int MAX_FRAME_BYTES = 64 * 1024;

    /** Encode one JSON string as a single length-prefixed frame. */
    public static ByteBuffer encode(String json) {
        if (json == null) throw new IllegalArgumentException("json must not be null");
        byte[] body = json.getBytes(UTF8);
        if (body.length > MAX_FRAME_BYTES) throw new IllegalArgumentException("frame is too large");
        ByteBuffer out = ByteBuffer.allocate(4 + body.length);
        out.putInt(body.length);
        out.put(body);
        out.flip();
        return out;
    }

    /**
     * Decode a single frame starting at the buffer's current position.
     *
     * @return the JSON string, or {@code null} if fewer than one full frame
     *         is available (the buffer position is left untouched then)
     */
    public static String decodeOne(ByteBuffer buf) {
        if (buf.remaining() < 4) return null;
        buf.mark();
        int len = buf.getInt();
        if (len < 0 || len > MAX_FRAME_BYTES || buf.remaining() < len) {
            buf.reset();
            return null;
        }
        byte[] body = new byte[len];
        buf.get(body);
        return new String(body, UTF8);
    }

    /** Decode every complete frame in the buffer, in order. */
    public static List<String> decodeAll(ByteBuffer buf) {
        List<String> out = new ArrayList<String>();
        for (;;) {
            String frame = decodeOne(buf);
            if (frame == null) break;
            out.add(frame);
        }
        return out;
    }
}
