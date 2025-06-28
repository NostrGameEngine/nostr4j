package org.ngengine.nostr4j.nip47;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Test;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.wallets.InvoiceData;
import org.ngengine.wallets.InvoiceProperties;
import org.ngengine.wallets.TransactionInfo;
import org.ngengine.wallets.TransactionType;
import org.ngengine.wallets.WalletInfo;
import org.ngengine.wallets.nip47.NWCException;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;

public class TestNWC {    
    private static final Logger logger = TestLogger.getRoot(Level.FINEST);

    private static final String WALLET_URI_1 = "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=e7ed7f71f3aebba15e125b9e19295951efd17e037f8ec95fe2afb9fd2b79ce57";
    private static final String WALLET_URI_2 = "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=cc958a9a7af7cb745e48d8079ecaf6bb86cfa0a0c844a86aa385caedffe3b087";
    private static final String WALLET_URI_NO_BUDGET = "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=60df78e1fccd135bcf4a513d2ddf9ce5cbf39ace1d10edff1db3f59d58d8955e";


    @Test
    public void testMakeInvoice() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        InvoiceData invoice = wallet.makeInvoice(new InvoiceProperties(
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
        InvoiceData invoice = wallet.makeInvoice(new InvoiceProperties(
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
        wallet2.payInvoice(
            invoice.invoice(),
            invoice.amountMsats()
        ).await();
    }
   
    @Test
    public void testInfo() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        WalletInfo info = wallet.getInfo().await();
        assertEquals(info.alias(),"Sandbox");
        assertEquals(info.network(),"mainnet");        
    }
   
    @Test
    public void testBalance() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        Long balance = wallet.getBalance().await();
        assertTrue(balance > 1000);
    }

    @Test
    public void testPayInvoiceWithoutBudget() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        InvoiceData invoice = wallet.makeInvoice(new InvoiceProperties(
                1000,
                "Test Invoice",
                null,
                null)).await();
        assertNotNull(invoice);
        assertEquals(invoice.amountMsats(), 1000);
        assertEquals(invoice.description(), "Test Invoice");
        assertNotNull(invoice.createdAt());
        assertNotNull(invoice.paymentHash());

        try{
            NWCWallet wallet2 = new NWCWallet(new NWCUri(WALLET_URI_NO_BUDGET));
            wallet2.payInvoice(
                    invoice.invoice(),
                    invoice.amountMsats()).await();
        } catch (Exception e) {
            assertTrue(e instanceof NWCException);
            assertEquals(((NWCException)e).getCode(), "QUOTA_EXCEEDED");
        }
    }

    @Test
    public void testLookup() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        InvoiceData invData = wallet.makeInvoice(new InvoiceProperties(
                1000,
                "Test Invoice",
                null,
                null)).await();
        assertNotNull(invData);
        assertEquals(invData.amountMsats(), 1000);
        assertEquals(invData.description(), "Test Invoice");
        assertNotNull(invData.createdAt());
        assertNotNull(invData.paymentHash());

        InvoiceData invData1 = wallet.lookupInvoice(invData.paymentHash(), null).await();
        assertEquals(invData1.invoice(), invData.invoice());

        InvoiceData invData2 = wallet.lookupInvoice(null, invData.invoice()).await();
        assertEquals(invData1.invoice(), invData2.invoice());

        List<TransactionInfo> transactions = wallet.listTransactions(
            Instant.now().minus(Duration.ofSeconds(1)),
            null, 
            null, 
            null, 
            true, 
            TransactionType.incoming
        ).await();

        System.out.println("invoice: " + invData2);
         for(TransactionInfo tx : transactions) {
            
            if(tx.invoice().equals(invData.invoice())){
                System.out.println("Transaction: " + tx);

                assertEquals(invData2.preimage(),tx.preimage());
                return;
            }
           
        }
        assertTrue(false);

    }

}