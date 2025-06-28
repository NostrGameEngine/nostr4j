package org.ngengine.nostr4j.nip47;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Test;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletInvoice;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletMakeInvoiceRequest;
import org.ngengine.nostr4j.wallet.pay.NostrWalletPayRequest;

public class TestNWC {    
        private static final Logger logger = TestLogger.getRoot(Level.FINEST);

    private static final String WALLET_URI_1 = "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=e7ed7f71f3aebba15e125b9e19295951efd17e037f8ec95fe2afb9fd2b79ce57";
    private static final String WALLET_URI_2 = "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=cc958a9a7af7cb745e48d8079ecaf6bb86cfa0a0c844a86aa385caedffe3b087";
    private static final String WALLET_URI_NO_BUDGET = "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=60df78e1fccd135bcf4a513d2ddf9ce5cbf39ace1d10edff1db3f59d58d8955e";


    @Test
    public void testMakeInvoice() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        NostrWalletInvoice invoice = wallet.invoice(new NostrWalletMakeInvoiceRequest(
            1000,
            "Test Invoice",
            null,
            null 
        )).await();
        assertNotNull(invoice);
        assertEquals(invoice.amountMsats(), 1000);
        assertEquals(invoice.description(), "Test Invoice");
        assertNotNull(invoice.createdAt());
        assertNotNull(invoice.paymentHash());
    }

    @Test
    public void testPayInvoice() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        NostrWalletInvoice invoice = wallet.invoice(new NostrWalletMakeInvoiceRequest(
                1000,
                "Test Invoice",
                null,
                null)).await();
        assertNotNull(invoice);
        assertEquals(invoice.amountMsats(), 1000);
        assertEquals(invoice.description(), "Test Invoice");
        assertNotNull(invoice.createdAt());
        assertNotNull(invoice.paymentHash());


        NWCWallet wallet2 = new NWCWallet(new NWCUri(WALLET_URI_2));
        wallet2.pay(new NostrWalletPayRequest(
            invoice.invoice(),
            invoice.amountMsats()
        )).await();
    }
   

   
}