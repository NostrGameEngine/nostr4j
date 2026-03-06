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

package org.ngengine.nostr4j.rtc.turn.event;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.NGEPlatform;

/**
 * Encode/Decode TURN packets (note: headers are decoded as SignedNostrEvent, but this class does not verify them).
 */
public final class NostrTURNCodec {

    public static final int VERSION = 2;
    private static final int ENVELOPE_PREFIX_SIZE = 1 + 8 + 2; // version + int64 vsocketId + uint16 header size

    public static byte[] encodeHeader(SignedNostrEvent ev) {
        return NGEPlatform.get().toJSON(ev.toMap()).getBytes(StandardCharsets.UTF_8);
    }

    public static long extractVsocketId(ByteBuffer frame) {
        frame = frame.duplicate().order(ByteOrder.BIG_ENDIAN);
        int version = frame.get() & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported TURN packet version: " + version);
        }
        return frame.getLong();
    }

    public static ByteBuffer withVsocketId(ByteBuffer frame, long vsocketId) {
        ByteBuffer source = frame.duplicate();
        ByteBuffer copy = ByteBuffer.allocate(source.remaining()).order(ByteOrder.BIG_ENDIAN);
        copy.put(source);
        copy.flip();
        int version = copy.get(0) & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported TURN packet version: " + version);
        }
        copy.putLong(1, vsocketId);
        return copy.asReadOnlyBuffer();
    }

    public static void decodePayloads(ByteBuffer frame, List<byte[]> payloads) {
        frame = frame.duplicate().order(ByteOrder.BIG_ENDIAN);
        int version = frame.get() & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported TURN packet version: " + version);
        }
        frame.getLong(); // vsocketId
        int headerSize = frame.getShort() & 0xFFFF;
        if (headerSize <= 0) {
            throw new IllegalArgumentException("Invalid TURN header size: " + headerSize);
        }
        frame.position(frame.position() + headerSize);
        int payloadCount = frame.getShort() & 0xFFFF;
        for (int i = 0; i < payloadCount; i++) {
            long payloadSize = frame.getInt() & 0xFFFFFFFFL;
            if (payloadSize > Integer.MAX_VALUE) throw new IllegalArgumentException("Payload too large: " + payloadSize);
            byte[] payloadBytes = new byte[(int) payloadSize];
            frame.get(payloadBytes);
            payloads.add(payloadBytes);
        }
    }

    public static ByteBuffer encodeFrame(byte[] header, long vsocketId, List<byte[]> payloads) {
        int size = ENVELOPE_PREFIX_SIZE + header.length + 2; // + payload count
        if (payloads != null) {
            for (byte[] payload : payloads) {
                size += 4; // payload size
                size += payload.length; // payload
            }
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) VERSION);
        buffer.putLong(vsocketId);
        buffer.putShort((short) header.length);
        buffer.put(header);
        if (payloads != null) {
            buffer.putShort((short) payloads.size());
            for (byte[] payload : payloads) {
                buffer.putInt(payload.length);
                buffer.put(payload);
            }
        } else {
            buffer.putShort((short) 0);
        }
        buffer.flip();
        return buffer.asReadOnlyBuffer();
    }

    public static SignedNostrEvent decodeHeader(ByteBuffer frame) {
        frame = frame.duplicate().order(ByteOrder.BIG_ENDIAN);
        int version = frame.get() & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported TURN packet version: " + version);
        }
        frame.getLong(); // vsocketId
        int headerSize = frame.getShort() & 0xFFFF;
        if (headerSize <= 0) {
            throw new IllegalArgumentException("Invalid TURN header size: " + headerSize);
        }
        byte[] headerBytes = new byte[headerSize];
        frame.get(headerBytes);
        return new SignedNostrEvent(NGEPlatform.get().fromJSON(new String(headerBytes, StandardCharsets.UTF_8), Map.class));
    }

    public static byte[] extractHeaderFrame(ByteBuffer frame) {
        frame = frame.duplicate().order(ByteOrder.BIG_ENDIAN);
        int version = frame.get() & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported TURN packet version: " + version);
        }
        long vsocketId = frame.getLong();
        int headerSize = frame.getShort() & 0xFFFF;
        if (headerSize <= 0) {
            throw new IllegalArgumentException("Invalid TURN header size: " + headerSize);
        }
        ByteBuffer headerFrame = ByteBuffer.allocate(ENVELOPE_PREFIX_SIZE + headerSize).order(ByteOrder.BIG_ENDIAN);
        headerFrame.put((byte) version);
        headerFrame.putLong(vsocketId);
        headerFrame.putShort((short) headerSize);
        byte[] headerBytes = new byte[headerSize];
        frame.get(headerBytes);
        headerFrame.put(headerBytes);
        return headerFrame.array();
    }

    public static boolean compareHeaders(ByteBuffer frame, byte[] headerFrame) {
        frame = frame.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (headerFrame == null) {
            return false;
        }

        if (headerFrame.length >= ENVELOPE_PREFIX_SIZE && (headerFrame[0] & 0xFF) == VERSION) {
            for (int i = 0; i < headerFrame.length; i++) {
                if (!frame.hasRemaining() || frame.get() != headerFrame[i]) {
                    return false;
                }
            }
            return true;
        }

        int version = frame.get() & 0xFF;
        if (version != VERSION) {
            return false;
        }
        frame.getLong(); // vsocketId
        int headerSize = frame.getShort() & 0xFFFF;
        if (headerSize != headerFrame.length) {
            return false;
        }
        for (int i = 0; i < headerSize; i++) {
            if (!frame.hasRemaining() || frame.get() != headerFrame[i]) {
                return false;
            }
        }
        return true;
    }
}
