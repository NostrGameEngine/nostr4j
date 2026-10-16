package org.ngengine.wallets.nip47;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestNWCAuth {

    @Test
    public void qrIsImmediatePublicAndUniqueAndRequiresCorrelatedApproval() throws Exception {
        NostrKeyPairSigner wallet = NostrKeyPairSigner.generate();
        try (
            NWCAuth first = new NWCAuth(
                List.of("wss://relay.example"),
                "N app",
                List.of("pay_invoice"),
                List.of("get_balance", "list_transactions")
            );
            NWCAuth second = new NWCAuth(List.of("wss://relay.example"), "N app", List.of("pay_invoice"), List.of())
        ) {
            URI uri = URI.create(first.getConnectionUri());
            assertEquals("nostr+walletauth", uri.getScheme());
            assertNotEquals(uri.getHost(), URI.create(second.getConnectionUri()).getHost());
            HashMap<String, String> query = new HashMap<String, String>();
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            assertFalse(query.containsKey("secret"));
            assertEquals(64, query.get("state").length());
            assertEquals("pay_invoice", query.get("request_methods"));
            assertEquals("get_balance list_transactions", query.get("optional_request_methods"));
            UnsignedNostrEvent response = new UnsignedNostrEvent()
                .withKind(13194)
                .withTag("p", uri.getHost())
                .withContent("pay_invoice get_balance");
            assertNull("A missing state must never connect", first.connectionFrom(wallet.sign(response).await()));
            response.withTag("state", query.get("state"));
            response.withTag("relay", "wss://wallet.example");
            NWCUri connection = first.connectionFrom(wallet.sign(response).await());
            assertNotNull(connection);
            assertEquals(List.of("wss://wallet.example"), connection.getRelays());
            assertEquals(uri.getHost(), connection.getSigner().getPublicKey().await().asHex());
            assertNull("A different pairing must reject the approval", second.connectionFrom(wallet.sign(response).await()));
        } finally {
            wallet.close();
        }
    }

    @Test
    public void closedSessionsCannotBeReused() throws Exception {
        NWCAuth auth = new NWCAuth(List.of("wss://relay.example"), "N app", List.of("pay_invoice"), List.of());
        auth.close();
        assertThrows(IllegalStateException.class, auth::getConnectionUri);
        assertTrue(auth.awaitConnection(Duration.ofMinutes(1)).isFailed());
    }
}
