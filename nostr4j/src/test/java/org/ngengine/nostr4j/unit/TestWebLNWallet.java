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

package org.ngengine.nostr4j.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;
import org.ngengine.bolt11.Bolt11;
import org.ngengine.bolt11.Bolt11Invoice;
import org.ngengine.bolt11.Bolt11Tag;
import org.ngengine.bolt11.Bolt11TagName;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.platform.AsyncTask;
import org.ngengine.wallets.InvoiceData;
import org.ngengine.wallets.InvoiceProperties;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.Wallet;
import org.ngengine.wallets.webln.WebLNWallet;

public class TestWebLNWallet {

    private static final class Provider extends WebLNWallet {

        Set<String> methods = Set.of("enable", "getInfo", "getBalance", "makeInvoice", "sendPayment");
        Map<String, Object> info = Map.of(
            "node",
            Map.of("alias", "Local test wallet", "pubkey", "test"),
            "methods",
            List.of("sendPayment", "makeInvoice")
        );
        Object balance = Map.of("balance", 12.5, "currency", "sats");
        String invoice;
        String preimage = "01".repeat(32);
        Throwable denied;
        boolean holdEnable;
        boolean holdPayment;
        Consumer<Object> enable;
        Consumer<Object> payment;
        final List<String> calls = new ArrayList<>();
        List<?> invoiceArgs;

        @Override
        protected AsyncTask<Boolean> hasMethod(String method) {
            return AsyncTask.completed(methods.contains(method));
        }

        @Override
        protected AsyncTask<Object> request(String method, List<?> args) {
            calls.add(method);
            if ("enable".equals(method)) {
                if (denied != null) return AsyncTask.failed(denied);
                if (holdEnable) return AsyncTask.create((resolve, reject) -> enable = resolve);
                return AsyncTask.completed(null);
            }
            if ("getInfo".equals(method)) return AsyncTask.completed(info);
            if ("getBalance".equals(method)) return AsyncTask.completed(balance);
            if ("makeInvoice".equals(method)) {
                invoiceArgs = args;
                return AsyncTask.completed(Map.of("paymentRequest", invoice));
            }
            if ("sendPayment".equals(method)) return holdPayment
                ? AsyncTask.create((resolve, reject) -> payment = resolve)
                : AsyncTask.completed(Map.of("preimage", preimage));
            return AsyncTask.failed(new UnsupportedOperationException(method));
        }
    }

    private String invoice(long msats, Instant created) throws Exception {
        Bolt11Invoice invoice = new Bolt11Invoice();
        invoice.setTimestamp(created);
        invoice.setMillisatoshis(BigInteger.valueOf(msats));
        invoice.setTags(
            List.of(
                Bolt11Tag.of(Bolt11TagName.PAYMENT_HASH, "02".repeat(32)),
                Bolt11Tag.of(Bolt11TagName.DESCRIPTION, "Local test only"),
                Bolt11Tag.of(Bolt11TagName.PAYMENT_SECRET, "03".repeat(32))
            )
        );
        NostrKeyPair key = new NostrKeyPair();
        try {
            return Bolt11.sign(Bolt11.encode(invoice), key.getPrivateKey().asHex()).getPaymentRequest();
        } finally {
            key.destroy();
        }
    }

    @Test
    public void explicitEnableIsCoalescedAndClosedWalletNeverBecomesReady() throws Exception {
        Provider wallet = new Provider();
        wallet.holdEnable = true;
        assertTrue(wallet.calls.isEmpty());
        assertFalse(wallet.isReady());
        AsyncTask<Boolean> first = wallet.waitForReady();
        assertSame(first, wallet.waitForReady());
        wallet.close();
        wallet.enable.accept(null);
        assertThrows(Exception.class, () -> first.await());
        assertEquals(List.of("enable"), wallet.calls);
        assertFalse(wallet.isReady());
    }

    @Test
    public void permissionsCanBeRetriedButNeverRequestedByAvailability() throws Exception {
        Provider wallet = new Provider();
        wallet.denied = new IllegalStateException("User declined");
        Wallet adapter = wallet;
        assertTrue(adapter.isAvailable().await());
        assertTrue(wallet.calls.isEmpty());
        assertThrows(Exception.class, () -> wallet.waitForReady().await());
        assertFalse(wallet.isReady());
        wallet.denied = null;
        assertTrue(wallet.waitForReady().await());
        assertTrue(wallet.isReady());
        wallet.close();
        assertFalse(adapter.isAvailable().await());
        assertThrows(Exception.class, () -> wallet.getInfo().await());
    }

    @Test
    public void absentProviderReturnsAFailedTaskAndAllowsLateInjection() throws Exception {
        Provider wallet = new Provider();
        wallet.methods = Set.of();
        AsyncTask<Boolean> pending = wallet.waitForReady();
        assertNotNull(pending);
        assertThrows(Exception.class, () -> pending.await());
        assertTrue(wallet.calls.isEmpty());
        wallet.methods = Set.of("enable", "getInfo");
        assertTrue(wallet.waitForReady().await());
        assertTrue(wallet.isReady());
        assertEquals(List.of("enable", "getInfo"), wallet.calls);
        wallet.close();
    }

    @Test
    public void balanceUsesMillisatoshisAndRejectsFiatAndMalformedValues() throws Exception {
        Provider wallet = new Provider();
        wallet.waitForReady().await();
        assertEquals(Long.valueOf(12500), wallet.getBalance().await());
        wallet.balance = Map.of("balance", 1.001);
        assertEquals(Long.valueOf(1001), wallet.getBalance().await());
        for (Object value : List.of(
            Map.of("balance", 12, "currency", "USD"),
            Map.of("balance", -1),
            Map.of("balance", "wrong"),
            Map.of("balance", Double.NaN),
            Map.of("balance", 0.0001)
        )) {
            wallet.balance = value;
            assertThrows(Exception.class, () -> wallet.getBalance().await());
        }
        wallet.methods = Set.of("enable", "getInfo", "sendPayment");
        assertFalse(wallet.isMethodSupported(Wallet.Methods.getBalance).await());
        assertFalse(wallet.isMethodSupported(Wallet.Methods.listTransactions).await());
        assertThrows(Exception.class, () -> wallet.listTransactions(null, null, null, null, false, null).await());
        assertThrows(Exception.class, () -> wallet.lookupInvoice("hash", null).await());
        wallet.close();
    }

    @Test
    public void makeInvoiceConvertsSatsAndUsesRealInvoiceExpiry() throws Exception {
        Provider wallet = new Provider();
        wallet.waitForReady().await();
        Instant created = Instant.now();
        wallet.invoice = invoice(21000, created);
        InvoiceData result = wallet
            .makeInvoice(new InvoiceProperties(21000, "Local test only", null, Duration.ofMinutes(30)))
            .await();
        assertEquals("21", ((Map<?, ?>) wallet.invoiceArgs.get(0)).get("amount"));
        assertEquals("Local test only", ((Map<?, ?>) wallet.invoiceArgs.get(0)).get("defaultMemo"));
        assertEquals(21000, result.amountMsats());
        assertEquals(result.createdAt().plusSeconds(3600), result.expiresAt());
        assertThrows(Exception.class, () -> wallet.makeInvoice(new InvoiceProperties(1001, null, null, null)).await());
        assertThrows(Exception.class, () -> wallet.makeInvoice(new InvoiceProperties(22000, null, null, null)).await());
        assertThrows(
            Exception.class,
            () -> wallet.makeInvoice(new InvoiceProperties(21000, null, "04".repeat(32), null)).await()
        );
        wallet.close();
    }

    @Test
    public void paymentRejectsOverridesExpiredRequestsAndMissingProofWithoutRetry() throws Exception {
        Provider wallet = new Provider();
        wallet.waitForReady().await();
        String invoice = invoice(21000, Instant.now());
        assertThrows(Exception.class, () -> wallet.payInvoice(invoice, 22000L).await());
        assertThrows(Exception.class, () -> wallet.payInvoice(invoice, 21000L, Instant.now().minusSeconds(1)).await());
        assertFalse(wallet.calls.contains("sendPayment"));
        assertEquals(wallet.preimage, wallet.payInvoice(invoice, 21000L).await().preimage());
        wallet.preimage = "invalid";
        assertThrows(Exception.class, () -> wallet.payInvoice(invoice, null).await());
        assertEquals(2, wallet.calls.stream().filter("sendPayment"::equals).count());
        wallet.close();
    }

    @Test
    public void receivingAddressIsOnlyAnOptionalSuggestion() throws Exception {
        Provider provider = new Provider();
        Wallet wallet = provider;
        wallet.waitForReady().await();
        assertNull(wallet.getReceivingAddress());
        provider.info = Map.of("lud16", "alice@example.com", "node", Map.of("alias", "Alice"));
        assertEquals("Alice", wallet.getInfo().await().alias());
        assertEquals("alice@example.com", wallet.getReceivingAddress());
        provider.info = Map.of("lud06", "lnurl1test");
        wallet.getInfo().await();
        assertEquals("lnurl1test", wallet.getReceivingAddress());
        wallet.close();
        assertNull(wallet.getReceivingAddress());
    }

    @Test
    public void expiredInFlightRequestFailsWithoutRepeatingThePayment() throws Exception {
        Provider wallet = new Provider();
        wallet.waitForReady().await();
        wallet.holdPayment = true;
        AsyncTask<PayResponse> pending = wallet.payInvoice(invoice(21000, Instant.now()), null, Instant.now().plusMillis(100));
        assertThrows(Exception.class, () -> pending.await());
        assertEquals(1, wallet.calls.stream().filter("sendPayment"::equals).count());
        wallet.payment.accept(Map.of("preimage", wallet.preimage));
        assertThrows(Exception.class, () -> pending.await());
        wallet.close();
    }

    @Test
    public void regularJvmHasNoBrowserWallet() throws Exception {
        Wallet wallet = new WebLNWallet();
        assertFalse(wallet.isAvailable().await());
        assertThrows(Exception.class, () -> wallet.waitForReady().await());
        wallet.close();
    }

    @Test
    public void concurrentReadinessChecksShareOnePermissionAttempt() throws Exception {
        Provider wallet = new Provider();
        wallet.holdEnable = true;
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AsyncTask<Boolean>>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                calls.add(
                    workers.submit(() -> {
                        start.await();
                        return wallet.waitForReady();
                    })
                );
            }
            start.countDown();
            AsyncTask<Boolean> first = calls.get(0).get(2, TimeUnit.SECONDS);
            for (Future<AsyncTask<Boolean>> call : calls) assertSame(first, call.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("enable"), wallet.calls);
            wallet.enable.accept(null);
            assertTrue(first.await());
            assertTrue(wallet.isReady());
            assertTrue(wallet.waitForReady().await());
            assertEquals(List.of("enable", "getInfo"), wallet.calls);
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            wallet.close();
        }
    }
}
