package org.ngengine.nostr4j.lnurl;

import static org.junit.Assert.assertEquals;

import java.net.URISyntaxException;

import org.junit.Test;
import org.ngengine.lnurl.LnUrl;
import org.ngengine.nostr4j.utils.Bech32.Bech32DecodingException;
import org.ngengine.nostr4j.utils.Bech32.Bech32EncodingException;
import org.ngengine.nostr4j.utils.Bech32.Bech32InvalidChecksumException;
import org.ngengine.nostr4j.utils.Bech32.Bech32InvalidRangeException;

public class TestLnUrl {
    @Test
    public void testEncodeDecode() throws Bech32DecodingException, Bech32InvalidChecksumException, Bech32InvalidRangeException, URISyntaxException, Bech32EncodingException{
        String lnurl = "lnurl1dp68gurn8ghj7unzd33zu6t59uh8wetvdskkkmn0wahz7mrww4excup00fshqeug7aa";
        String plain = "https://rblb.it/.well-known/lnurlp/zap";
        LnUrl lnUrlDec = new LnUrl(lnurl);
        LnUrl lnUrlDec2 = new LnUrl(lnurl.toLowerCase());
        assertEquals(lnUrlDec.toURI().toString(), plain);
        assertEquals(lnUrlDec.toString().toLowerCase(), lnurl.toLowerCase());
        assertEquals(lnUrlDec2.toURI().toString(), plain);
        assertEquals(lnUrlDec2.toString().toLowerCase(), lnurl.toLowerCase());

        LnUrl lnUrlEnc = LnUrl.encode(plain);
        LnUrl lnUrlEnc2 = LnUrl.encode(lnUrlDec.toURI());
        assertEquals(lnUrlEnc.toString().toLowerCase(), lnurl.toLowerCase());
        assertEquals(lnUrlEnc2.toString().toLowerCase(), lnurl.toLowerCase());
    }
    

}

