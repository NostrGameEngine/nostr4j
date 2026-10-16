package org.ngengine.nostr4j.nip57;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.ngengine.lnurl.LnAddress;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public class TestZapPayEndpoint {

    @Test
    public void standardFlagAndLegacyAliasAreParsedWithoutIgnoringRefusals() throws Exception {
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        try {
            LinkedHashMap<String, Object> data = new LinkedHashMap<String, Object>(
                Map.of(
                    "tag",
                    "payRequest",
                    "minSendable",
                    1000,
                    "maxSendable",
                    1000000,
                    "callback",
                    "https://example.com/pay",
                    "metadata",
                    "[[\"text/plain\",\"Fixture\"]]",
                    "nostrPubkey",
                    signer.getPublicKey().await().asHex()
                )
            );
            assertFalse(Nip57.parsePayEndpoint(data).isNostrAllowed());
            data.put("allowsNostr", true);
            assertTrue(Nip57.parsePayEndpoint(data).isNostrAllowed());
            LnAddress address = new LnAddress("index@example.com");
            SignedNostrEvent request = signer
                .sign(new UnsignedNostrEvent().withKind(9734).withTag("lnurl", address.toBech32()))
                .await();
            String callback = Nip57
                .parsePayEndpoint(data)
                .getCallback(1000, "", null, NGEUtils.getPlatform().toJSON(request.toMap()))
                .toString();
            assertTrue(callback.contains("&nostr="));
            assertTrue(callback.contains("&lnurl=" + address.toBech32()));
            data.put("allowsNostr", false);
            data.put("allowNostr", true);
            assertFalse(Nip57.parsePayEndpoint(data).isNostrAllowed());
            data.remove("allowsNostr");
            assertTrue(Nip57.parsePayEndpoint(data).isNostrAllowed());
            data.put("allowsNostr", "true");
            assertFalse(Nip57.parsePayEndpoint(data).isNostrAllowed());
            data.put("allowsNostr", true);
            data.put("nostrPubkey", "invalid");
            assertThrows(IllegalArgumentException.class, () -> Nip57.parsePayEndpoint(data));
        } finally {
            signer.getKeyPair().destroy();
        }
    }

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
