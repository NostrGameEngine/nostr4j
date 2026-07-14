/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.nostr4j.nip47;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Test;
import org.ngengine.nostr4j.TestEnvironment;
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

    private static final String WALLET_URI_1 = TestEnvironment.nwcWallet1();
    private static final String WALLET_URI_2 = TestEnvironment.nwcWallet2();
    private static final String WALLET_URI_NO_BUDGET = TestEnvironment.nwcNoBudget();

    @Test
    public void testMakeInvoice() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        InvoiceData invoice = wallet.makeInvoice(new InvoiceProperties(1000, "Test Invoice", null, null)).await();
        assertNotNull(invoice);
        assertEquals(invoice.amountMsats(), 1000);
        assertEquals(invoice.description(), "Test Invoice");
        assertNotNull(invoice.createdAt());
        assertNotNull(invoice.paymentHash());
    }

    @Test
    public void testPayInvoice() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        InvoiceData invoice = wallet.makeInvoice(new InvoiceProperties(1000, "Test Invoice", null, null)).await();
        assertNotNull(invoice);
        assertEquals(invoice.amountMsats(), 1000);
        assertEquals(invoice.description(), "Test Invoice");
        assertNotNull(invoice.createdAt());
        assertNotNull(invoice.paymentHash());

        NWCWallet wallet2 = new NWCWallet(new NWCUri(WALLET_URI_2));
        wallet2.payInvoice(invoice.invoice(), invoice.amountMsats()).await();
    }

    @Test
    public void testInfo() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        WalletInfo info = wallet.getInfo().await();
        assertEquals(info.alias(), "Sandbox");
        assertEquals(info.network(), "mainnet");
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
        InvoiceData invoice = wallet.makeInvoice(new InvoiceProperties(1000, "Test Invoice", null, null)).await();
        assertNotNull(invoice);
        assertEquals(invoice.amountMsats(), 1000);
        assertEquals(invoice.description(), "Test Invoice");
        assertNotNull(invoice.createdAt());
        assertNotNull(invoice.paymentHash());

        try {
            NWCWallet wallet2 = new NWCWallet(new NWCUri(WALLET_URI_NO_BUDGET));
            wallet2.payInvoice(invoice.invoice(), invoice.amountMsats()).await();
            fail("The zero-budget NWC connection must reject payments");
        } catch (Exception e) {
            assertTrue(e instanceof NWCException);
            assertEquals(((NWCException) e).getCode(), "QUOTA_EXCEEDED");
        }
    }

    @Test
    public void testLookup() throws Exception {
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET_URI_1));
        InvoiceData invData = wallet.makeInvoice(new InvoiceProperties(1000, "Test Invoice", null, null)).await();
        assertNotNull(invData);
        assertEquals(invData.amountMsats(), 1000);
        assertEquals(invData.description(), "Test Invoice");
        assertNotNull(invData.createdAt());
        assertNotNull(invData.paymentHash());

        NWCWallet payer = new NWCWallet(new NWCUri(WALLET_URI_2));
        payer.payInvoice(invData.invoice(), invData.amountMsats()).await();

        InvoiceData invData1 = wallet.lookupInvoice(invData.paymentHash(), null).await();
        assertEquals(invData1.invoice(), invData.invoice());

        InvoiceData invData2 = wallet.lookupInvoice(null, invData.invoice()).await();
        assertEquals(invData1.invoice(), invData2.invoice());

        List<TransactionInfo> transactions = wallet
            .listTransactions(
                invData.createdAt().minus(Duration.ofSeconds(1)),
                null,
                null,
                null,
                true,
                TransactionType.incoming
            )
            .await();

        System.out.println("invoice: " + invData2);
        for (TransactionInfo tx : transactions) {
            if (tx.invoice().equals(invData.invoice())) {
                System.out.println("Transaction: " + tx);

                assertEquals(invData2.preimage(), tx.preimage());
                return;
            }
        }
        assertTrue(false);
    }
}
