package org.ngengine.nostr4j.lnurl;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import org.junit.Test;
import org.ngengine.lnurl.LnAddress;
import org.ngengine.lnurl.services.LnUrlPayRequest;
import org.ngengine.lnurl.services.LnUrlPayerData;
import org.ngengine.lnurl.services.LnUrlPaymentResponse;
import org.ngengine.nostr4j.utils.Bech32.Bech32DecodingException;
import org.ngengine.nostr4j.utils.Bech32.Bech32EncodingException;
import org.ngengine.nostr4j.utils.Bech32.Bech32InvalidChecksumException;
import org.ngengine.nostr4j.utils.Bech32.Bech32InvalidRangeException;
import org.ngengine.platform.AsyncTask;

public class TestLnAddress {
    
    @Test
    public void testLnAddress() throws Bech32EncodingException, URISyntaxException, Bech32DecodingException, Bech32InvalidChecksumException, Bech32InvalidRangeException {
        String lnAddress = "zap@rblb.it";
        String lnUrl = "https://rblb.it/.well-known/lnurlp/zap";
        LnAddress lnAddressObj = new LnAddress(lnAddress);
        assertEquals(lnAddressObj.toURI().toString(), lnUrl);
    }


    @Test
    public void getInvoice() throws Exception {
        LnAddress lnAddressObj = new LnAddress("unit@ln.rblb.it");
        LnUrlPayRequest service = (LnUrlPayRequest) lnAddressObj.getService().await();
        assertEquals(service.canSend(1000),true);
        LnUrlPayerData payerData = new LnUrlPayerData();
        payerData.setName("Test Payer");
        
        URI callback = service.getCallback(1000, "test payment", payerData);
        System.out.println("Callback URL: " + callback);
        assertEquals(callback.toString().contains("Test+Payer"), true);
            

        LnUrlPaymentResponse resp = service.fetchInvoice(1000,"test payment", payerData).await();
        assertEquals(resp.getPr().startsWith("lnbc"), true);


    }
}
