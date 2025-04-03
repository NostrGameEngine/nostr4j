package org.ngengine.nostr4j.unit;

import org.junit.Test;
import org.ngengine.nostr4j.utils.NostrUtils;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
public class TestUtils {
    
    @Test
    public void testHexConversion(){
        byte bytes[] = {0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08};
        String hex = "0102030405060708";
        
        String derivedHex = NostrUtils.bytesToHex(ByteBuffer.wrap(bytes));
        assertEquals(hex, derivedHex);

        byte derivedBytes[] = NostrUtils.hexToBytes(hex).array();
        assertArrayEquals(bytes, derivedBytes);

    }
}
