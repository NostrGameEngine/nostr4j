package org.ngengine.nostr4j.platform.jvm;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

class Util  {

    public static byte[] bytesFromInt(int n) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(n).array();
    }

    public static byte[] bytesFromBigInteger(BigInteger n) {

        byte[] b = n.toByteArray();

        if(b.length == 32) {
            return b;
        }
        if(b.length > 32) {
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
        if(ret==null){
            ret = new byte[b0.length];
        }
        if(ret.length != b0.length){
            throw new IllegalArgumentException("The length of the return array must be equal to the length of the input arrays");
        }
        
        for(int i=0; i<b0.length; i++){
            ret[i] = (byte) (b0[i] ^ b1[i]);
        }

        return ret;
    }

}
