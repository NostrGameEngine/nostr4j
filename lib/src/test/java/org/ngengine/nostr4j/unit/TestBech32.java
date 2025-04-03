package org.ngengine.nostr4j.unit;

import org.junit.Test;
import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.nostr4j.utils.Bech32.Bech32Exception;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
public class TestBech32 {
    private static String[] VALID = {
        "A12UEL5L",
        "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
        "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
        "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j",
        "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
        "nsec1v4gj83ph04flwe940mkkr9fnxv0s7r85pqjj3kwuhdg8455f460q08upxx",
        "npub1wpuq4mcuhnxhnrqk85hk29qjz6u93vpzxqy9qpuugpyc302fepkqg8t3a4"
    };

    private static String[] INVALID = {
        "npub1wpuq4mcuDFxhnrqk85hk29qjz6u93vpzxqy9qpuugpyc302fepkqg8t3a4"
    };

    @Test
    public void bech32Checksum() throws Exception {
        for (String s : VALID) {
            Bech32.bech32Decode(s);
        }
    }
 
    @Test
    public void bech32DecodeEncode() throws Exception {
        for (String s : VALID) {                                    
            ByteBuffer decoded = Bech32.bech32Decode(s);
            String encoded = Bech32.bech32Encode(
                s.substring(0,s.lastIndexOf('1')).getBytes(),  
                decoded,
                new byte[6]
            );
            assertEquals(s.toLowerCase(), encoded.toLowerCase());        
            ByteBuffer decoded2 = Bech32.bech32Decode(encoded);
            assertEquals(decoded, decoded2);    
        }
    }

    @Test
    public void invalidCheckSum() throws Exception {
        for (String s : INVALID) {
            try {
                Bech32.bech32Decode(s);
                fail("Expected Bech32DecodingException");
            } catch (Exception e) {
                // Expected
            }
        }
    }


    @Test
    public void randomData() throws Bech32Exception{
        byte[] hrp = new byte[5];
        byte[] data = new byte[32];
        for(int i=0;i<hrp.length;i++){
            hrp[i] = "a".getBytes()[0];
        }
        for(int i=0;i<data.length;i++){
            data[i] = (byte)(Math.random()*256);
        }
        String encoded = Bech32.bech32Encode(hrp,ByteBuffer.wrap(data)        
        , new byte[6]);
        System.out.println("Encoded: "+encoded);
        ByteBuffer decoded = Bech32.bech32Decode(encoded);
        assertEquals(ByteBuffer.wrap(data), decoded);
    }
    
}
