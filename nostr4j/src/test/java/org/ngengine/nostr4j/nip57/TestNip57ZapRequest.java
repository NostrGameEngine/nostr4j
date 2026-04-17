package org.ngengine.nostr4j.nip57;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEPlatform;

public class TestNip57ZapRequest {

    private static final String SENDER = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String RECIPIENT = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String PROVIDER = "1111111111111111111111111111111111111111111111111111111111111111";

    private Map<String, Object> buildZapRequestMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "zap-request-id");
        map.put("pubkey", SENDER);
        map.put("kind", 9734);
        map.put("content", "Zap!");
        map.put("created_at", 1742147457L);
        map.put(
            "tags",
            Arrays.asList(
                Arrays.asList("relays", "wss://relay.one", "wss://relay.two"),
                Arrays.asList("amount", "21000"),
                Arrays.asList("lnurl", "lnurl1example"),
                Arrays.asList("p", RECIPIENT),
                Arrays.asList("e", "event123"),
                Arrays.asList("k", "1")
            )
        );
        map.put("sig", "zap-request-sig");
        return map;
    }

    @Test
    public void testZapRequestWrapperGetters() {
        ZapRequest zapRequest = new ZapRequest(buildZapRequestMap());

        assertEquals(21000L, zapRequest.getAmountMsats());
        assertEquals("lnurl1example", zapRequest.getLnurl());
        assertEquals(NostrPublicKey.fromHex(RECIPIENT), zapRequest.getRecipient());
        assertEquals(NostrPublicKey.fromHex(SENDER), zapRequest.getSender());
        assertEquals(Arrays.asList("wss://relay.one", "wss://relay.two"), zapRequest.getRelays());
        assertEquals(new NostrEvent.Coordinates("e", "1", "event123"), zapRequest.getZappedEventCoordinates());
    }

    @Test
    public void testZapReceiptAndInvoiceExposeTypedZapRequest() {
        Map<String, Object> zapRequestMap = buildZapRequestMap();
        String zapRequestJson = NGEPlatform.get().toJSON(zapRequestMap);

        Map<String, Object> receiptMap = new LinkedHashMap<>();
        receiptMap.put("id", "zap-receipt-id");
        receiptMap.put("pubkey", PROVIDER);
        receiptMap.put("kind", 9735);
        receiptMap.put("content", "");
        receiptMap.put("created_at", 1742147460L);
        receiptMap.put(
            "tags",
            Arrays.asList(
                Arrays.asList("description", zapRequestJson),
                Arrays.asList("bolt11", "lnbc1example"),
                Arrays.asList("p", RECIPIENT),
                Arrays.asList("P", SENDER),
                Arrays.asList("e", "event123"),
                Arrays.asList("k", "1")
            )
        );
        receiptMap.put("sig", "zap-receipt-sig");

        ZapReceipt receipt = new ZapReceipt(receiptMap);
        ZapRequest requestFromReceipt = receipt.getZapRequestEvent();

        assertNotNull(requestFromReceipt);
        assertEquals(21000L, requestFromReceipt.getAmountMsats());
        assertEquals(NostrPublicKey.fromHex(SENDER), receipt.getSender());

        SignedNostrEvent signedRequest = new SignedNostrEvent(zapRequestMap);
        ZapInvoice invoice = new ZapInvoice("lnbc1example", "Zap!", signedRequest, NostrPublicKey.fromHex(PROVIDER));

        assertTrue(invoice.getZapRequest() instanceof ZapRequest);
        assertEquals("lnurl1example", invoice.getZapRequest().getLnurl());
    }

    @Test
    public void testZapReceiptRequiredTagsAreEnforced() {
        Map<String, Object> receiptMap = new LinkedHashMap<>();
        receiptMap.put("id", "zap-receipt-id");
        receiptMap.put("pubkey", PROVIDER);
        receiptMap.put("kind", 9735);
        receiptMap.put("content", "");
        receiptMap.put("created_at", 1742147460L);
        receiptMap.put("tags", Arrays.asList(Arrays.asList("bolt11", "lnbc1example"), Arrays.asList("p", RECIPIENT)));
        receiptMap.put("sig", "zap-receipt-sig");

        ZapReceipt receipt = new ZapReceipt(receiptMap);
        try {
            receipt.getZapRequestEvent();
            fail("Expected missing description tag to throw");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void testZapInvoiceRequiredFieldsAreEnforced() {
        SignedNostrEvent signedRequest = new SignedNostrEvent(buildZapRequestMap());
        try {
            new ZapInvoice("", "", signedRequest, NostrPublicKey.fromHex(PROVIDER));
            fail("Expected empty invoice to throw");
        } catch (IllegalArgumentException expected) {}

        try {
            new ZapInvoice("lnbc1example", "", signedRequest, null);
            fail("Expected null provider pubkey to throw");
        } catch (NullPointerException expected) {}
    }
}