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
package org.ngengine.nostr4j.platform.jvm;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

class Util {

    public static byte[] bytesFromInt(int n) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(n).array();
    }

    public static byte[] bytesFromBigInteger(BigInteger n) {
        byte[] b = n.toByteArray();

        if (b.length == 32) {
            return b;
        }
        if (b.length > 32) {
            return Arrays.copyOfRange(b, b.length - 32, b.length);
        }

        byte[] buf = new byte[32];
        System.arraycopy(b, 0, buf, buf.length - b.length, b.length);
        return buf;
    }

    public static BigInteger bigIntFromBytes(byte[] b) {
        return new BigInteger(1, b);
    }

    public static BigInteger bigIntFromBytes(byte[] bytes, int offset, int length) {
        if (bytes.length == 0) return BigInteger.ZERO;

        // Calculate actual data length by skipping leading zeros
        int dataStart = offset;
        while (dataStart < offset + length && bytes[dataStart] == 0) {
            dataStart++;
        }

        // If all bytes are zero, return zero
        if (dataStart >= offset + length) return BigInteger.ZERO;

        // Create a byte array with leading 0x00 to ensure positive representation
        int dataLength = offset + length - dataStart;
        byte[] result = new byte[dataLength + 1];
        System.arraycopy(bytes, dataStart, result, 1, dataLength);

        return new BigInteger(result);
    }

    public static byte[] xor(byte[] b0, byte[] b1, byte ret[]) {
        if (b0.length != b1.length) {
            throw new IllegalArgumentException("The length of the input arrays must be equal");
        }
        if (ret == null) {
            ret = new byte[b0.length];
        }
        if (ret.length != b0.length) {
            throw new IllegalArgumentException(
                "The length of the return array must be equal to the length of the input arrays"
            );
        }

        for (int i = 0; i < b0.length; i++) {
            ret[i] = (byte) (b0[i] ^ b1[i]);
        }

        return ret;
    }
}
