package org.ngengine.nostr4j.nip57;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestZapInvoice {

    @Test
    public void invoiceSerializationDoesNotMutateTheSignedRequest() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            SignedNostrEvent request = signer.sign(new UnsignedNostrEvent().withKind(9734).withContent("Fixture")).await();
            ZapInvoice invoice = new ZapInvoice("synthetic transport fixture", "", request, signer.getPublicKey().await());
            LinkedHashMap<String, Object> original = new LinkedHashMap<>(invoice.getZapRequest().toMap());
            Map<String, Object> transport = invoice.toMap();
            assertEquals("synthetic transport fixture", transport.get("invoice"));
            assertEquals(original, transport.get("request"));
            transport.put("content", "Transport edit");
            assertEquals(original, invoice.getZapRequest().toMap());
            assertTrue(invoice.getZapRequest().verify());
        } finally {
            signer.getKeyPair().destroy();
        }
    }
}
