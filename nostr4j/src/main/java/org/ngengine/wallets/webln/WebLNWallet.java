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

package org.ngengine.wallets.webln;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.ngengine.bolt11.Bolt11;
import org.ngengine.bolt11.Bolt11Invoice;
import org.ngengine.bolt11.Bolt11TagName;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.wallets.InvoiceData;
import org.ngengine.wallets.InvoiceProperties;
import org.ngengine.wallets.InvoiceType;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.TransactionInfo;
import org.ngengine.wallets.TransactionType;
import org.ngengine.wallets.Wallet;
import org.ngengine.wallets.WalletInfo;

/**
 * WebLN browser wallet, accessed through the platform's JavaScript function bridge.
 * Constructing a wallet never prompts for permissions; call waitForReady() from a user action.
 * Providers do not expose standard invoice lookup or transaction history APIs.
 */
public class WebLNWallet implements Wallet {
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile AsyncTask<Boolean> enabling;
    private volatile String receivingAddress;
    private final AsyncExecutor executor;
    private final Runnable closer;
    private final Set<Consumer<Throwable>> pending = ConcurrentHashMap.newKeySet();

    /**
     * Creates a browser wallet adapter without requesting provider permissions.
     * Invoke waitForReady() from a user action to enable the provider. Closing the adapter
     * releases its executor and pending local waits; it cannot revoke a submitted payment.
     */
    public WebLNWallet() {
        executor = NGEPlatform.get().newAsyncExecutor(WebLNWallet.class);
        closer = NGEPlatform.get().registerFinalizer(this, executor::close);
    }

    /**
     * Invokes a method on the browser's WebLN provider through the platform bridge.
     * The default target is window.webln. Hosts without a window, such as SharedWorkers,
     * can override this method and the capability probe to route calls to their owner.
     *
     * @param method provider method name without the window.webln prefix
     * @param args arguments forwarded to the provider
     * @return task containing the provider's result or failure
     */
    protected AsyncTask<Object> request(String method, List<?> args) {
        return AsyncTask.create((resolve, reject) -> {
            try { NGEPlatform.get().callFunction("window.webln." + method, args, resolve, reject); }
            catch (Exception error) { reject.accept(error); }
        });
    }

    /**
     * Checks for a browser runtime and a provider method through the default browser bridge.
     * Override together with request() when routing provider calls through another host.
     *
     * @param method provider method name without the window.webln prefix
     * @return whether the provider exposes the requested method
     */
    protected AsyncTask<Boolean> hasMethod(String method) {
        return AsyncTask.create((resolve, reject) -> {
            try {
                NGEPlatform platform = NGEPlatform.get();
                if (!platform.getPlatformName().contains("(browser)")) {
                    resolve.accept(false);
                    return;
                }
                platform.canCallFunction("window.webln." + method, resolve);
            }
            catch (Exception error) { reject.accept(error); }
        });
    }

    /**
     * Checks for the provider's enable method without asking for permissions.
     * Availability does not imply that the provider is enabled or supports every wallet method.
     *
     * @return whether this open adapter can reach a WebLN provider
     */
    @Override
    public AsyncTask<Boolean> isAvailable() { return closed ? AsyncTask.completed(false) : hasMethod("enable"); }

    /**
     * Returns the optional receiving-address suggestion reported by the provider.
     * The value comes from lud16, lightningAddress or lud06 and may be absent. It is not
     * inferred from the node identity, and publishing it requires the user's consent.
     *
     * @return the last provider suggestion, or null when unavailable or cleared on close
     */
    public String getReceivingAddress() { return receivingAddress; }

    /**
     * Enables the provider and loads its wallet information.
     * Call this from a user action because enable may prompt for permission. Concurrent
     * callers share one attempt; a failed attempt can be retried explicitly. Ready wallets
     * and existing attempts use volatile reads before the initialization monitor.
     *
     * @return task reporting readiness, or a failure when the provider is unavailable or closed
     */
    @Override
    public AsyncTask<Boolean> waitForReady() {
        if (closed) return AsyncTask.failed(new IllegalStateException("Browser wallet is closed"));
        if (ready) return AsyncTask.completed(true);
        AsyncTask<Boolean> current = enabling;
        if (current != null) return current;
        synchronized (this) {
            if (closed) return AsyncTask.failed(new IllegalStateException("Browser wallet is closed"));
            if (ready) return AsyncTask.completed(true);
            if (enabling != null) return enabling;
            AsyncTask<Boolean> attempt = isAvailable().compose(available -> {
                if (!available) return AsyncTask.failed(new IllegalStateException("No WebLN browser wallet is available"));
                return call("enable", List.of(), null).compose(enabled -> {
                    if (closed) return AsyncTask.failed(new IllegalStateException("Browser wallet was disconnected"));
                    return call("getInfo", List.of(), null).then(info -> {
                        if (closed) throw new IllegalStateException("Browser wallet was disconnected");
                        rememberAddress(info);
                        ready = true;
                        return true;
                    });
                });
            });
            enabling = attempt;
            // A completed failure may run its handler synchronously. Return the task,
            // not the field that handler clears, and never clear a subsequent attempt.
            attempt.catchException(error -> { synchronized (this) { if(enabling == attempt) enabling = null; } });
            return attempt;
        }
    }

    private void rememberAddress(Object info) {
        if (!(info instanceof Map)) throw new IllegalArgumentException("Invalid WebLN wallet information");
        Map<?, ?> fields = (Map<?, ?>) info;
        receivingAddress = null;
        // These optional extension fields are deliberately not inferred from node pubkeys or aliases.
        for (String field : List.of("lud16", "lightningAddress", "lud06")) {
            Object value = fields.get(field);
            if (value instanceof String && !((String) value).isBlank() && ((String) value).length() <= 2048) {
                receivingAddress = (String) value;
                return;
            }
        }
    }

    @Override public boolean isReady() { return ready && !closed; }
    @Override public void close() {
        List<Consumer<Throwable>> waiting;
        synchronized (this) {
            if (closed) return;
            closed = true; ready = false; receivingAddress = null;
            waiting = List.copyOf(pending); pending.clear();
        }
        for (Consumer<Throwable> reject : waiting) reject.accept(new IllegalStateException("Browser wallet was disconnected; check payment status before retrying"));
        closer.run();
    }

    private AsyncTask<Object> call(String method, List<?> args, Instant deadline) {
        return AsyncTask.create((resolve, reject) -> {
            synchronized (this) {
                if (closed) { reject.accept(new IllegalStateException("Browser wallet is closed")); return; }
                pending.add(reject);
            }
            AsyncTask<?> timer = deadline == null ? null : executor.runLater(() -> {
                pending.remove(reject);
                reject.accept(new IllegalStateException("Browser wallet request expired; a submitted payment may still settle. Do not retry automatically."));
                return null;
            }, Math.max(0, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
            Runnable finish = () -> { if (timer != null) timer.cancel(); pending.remove(reject); };
            try {
                AsyncTask<Object> response;
                synchronized (this) {
                    if (closed || !pending.contains(reject)) { finish.run(); return; }
                    response = request(method, args);
                }
                response.then(result -> { finish.run(); resolve.accept(result); return null; })
                        .catchException(error -> { finish.run(); reject.accept(error); });
            } catch (Exception error) { finish.run(); reject.accept(error); }
        });
    }

    @Override
    public AsyncTask<Boolean> isMethodSupported(Methods method) {
        if (closed) return AsyncTask.completed(false);
        switch (method) {
            case payInvoice: return hasMethod("sendPayment");
            case makeInvoice: return hasMethod("makeInvoice");
            case getBalance: return hasMethod("getBalance");
            case getInfo: return hasMethod("getInfo");
            default: return AsyncTask.completed(false);
        }
    }

    private AsyncTask<Object> invoke(Methods capability, String method, List<?> args, Instant deadline) {
        if (!isReady()) return AsyncTask.failed(new IllegalStateException("Connect the browser wallet first"));
        return isMethodSupported(capability).compose(supported -> {
            if (!supported) return AsyncTask.failed(new UnsupportedOperationException("This browser wallet does not support " + method));
            if (!isReady()) return AsyncTask.failed(new IllegalStateException("Browser wallet was disconnected"));
            if (deadline != null && !deadline.isAfter(Instant.now()))
                return AsyncTask.failed(new IllegalStateException("Wallet request has expired"));
            // An extension payment cannot be cancelled once submitted. Do not retry it on timeout.
            return call(method, args, deadline);
        }).then(result -> {
            if (closed) throw new IllegalStateException("Browser wallet was disconnected; check payment status before retrying");
            return result;
        });
    }

    @Override
    public AsyncTask<PayResponse> payInvoice(String invoice, Long amountMsats, Instant deadline) {
        if (amountMsats != null) {
            try {
                Bolt11Invoice decoded = Bolt11.decode(invoice);
                if (amountMsats <= 0 || decoded.getMillisatoshis() == null
                        || !decoded.getMillisatoshis().equals(BigInteger.valueOf(amountMsats)))
                    return AsyncTask.failed(new IllegalArgumentException("WebLN cannot override an invoice amount"));
            } catch (Exception error) { return AsyncTask.failed(new IllegalArgumentException("Invalid Lightning invoice", error)); }
        }
        return invoke(Methods.payInvoice, "sendPayment", List.of(invoice), deadline).then(value -> {
            Object proof = ((Map<?, ?>) value).get("preimage");
            if (!(proof instanceof String) || !((String) proof).matches("[a-fA-F0-9]{64}"))
                throw new IllegalStateException("Browser wallet returned no payment proof; check wallet history before retrying");
            return new PayResponse((String) proof, null, null);
        });
    }

    @Override
    public AsyncTask<InvoiceData> makeInvoice(InvoiceProperties properties, Instant deadline) {
        if (properties.amountMsats() < 0 || properties.amountMsats() % 1000 != 0)
            return AsyncTask.failed(new IllegalArgumentException("WebLN invoices require a whole satoshi amount"));
        if (properties.descriptionHash() != null)
            return AsyncTask.failed(new UnsupportedOperationException("WebLN does not specify description-hash invoices"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("amount", String.valueOf(properties.amountMsats() / 1000));
        if (properties.description() != null) args.put("defaultMemo", properties.description());
        // WebLN has no expiry argument. Report the returned invoice's actual expiry, not the requested hint.
        return invoke(Methods.makeInvoice, "makeInvoice", List.of(args), deadline).then(value -> {
            Object invoice = ((Map<?, ?>) value).get("paymentRequest");
            if (!(invoice instanceof String)) throw new IllegalArgumentException("Browser wallet returned no invoice");
            Bolt11Invoice decoded = Bolt11.decode((String) invoice);
            long amount = decoded.getMillisatoshis() == null ? 0 : decoded.getMillisatoshis().longValueExact();
            if (amount != properties.amountMsats()) throw new IllegalArgumentException("Browser wallet returned a different invoice amount");
            return new InvoiceData(InvoiceType.incoming, (String) invoice, properties.description(), null, null,
                    decoded.getTag(Bolt11TagName.PAYMENT_HASH).getValueAsString(), amount, 0,
                    decoded.getTimestamp(), decoded.getTimeExpireDate() == null
                    ? decoded.getTimestamp().plusSeconds(3600) : decoded.getTimeExpireDate(), null, null);
        });
    }

    @Override
    public AsyncTask<Long> getBalance(Instant deadline) {
        return invoke(Methods.getBalance, "getBalance", List.of(), deadline).then(value -> {
            Map<?, ?> data = (Map<?, ?>) value;
            Object currency = data.get("currency");
            if (currency != null && !"sats".equals(currency)) throw new UnsupportedOperationException("Wallet balance is not in satoshis");
            Object balance = data.get("balance");
            if (!(balance instanceof Number)) throw new IllegalArgumentException("Invalid WebLN balance");
            long msats = new BigDecimal(balance.toString()).movePointRight(3).longValueExact();
            if (msats < 0 || msats > 9007199254740991L)
                throw new IllegalArgumentException("Invalid WebLN balance precision");
            return msats;
        });
    }

    @Override
    public AsyncTask<WalletInfo> getInfo(Instant deadline) {
        return invoke(Methods.getInfo, "getInfo", List.of(), deadline).then(value -> {
            rememberAddress(value);
            Map<?, ?> data = (Map<?, ?>) value;
            Map<?, ?> node = data.get("node") instanceof Map ? (Map<?, ?>) data.get("node") : Map.of();
            List<String> methods = data.get("methods") instanceof List ? ((List<?>) data.get("methods"))
                    .stream().filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toList()) : List.of();
            return new WalletInfo(text(node, "alias"), text(node, "color"), text(node, "pubkey"), "", 0, "", methods, List.of());
        });
    }

    private static String text(Map<?, ?> data, String key) { return data.get(key) instanceof String ? (String) data.get(key) : ""; }

    @Override
    public AsyncTask<InvoiceData> lookupInvoice(String hash, String invoice, Instant deadline) {
        return AsyncTask.failed(new UnsupportedOperationException("WebLN does not provide invoice lookup; check your wallet"));
    }

    @Override
    public AsyncTask<List<TransactionInfo>> listTransactions(Instant from, Instant until, Integer limit, Integer offset,
            boolean includeUnpaid, TransactionType type, Instant deadline) {
        return AsyncTask.failed(new UnsupportedOperationException("WebLN does not provide transaction history; open your wallet"));
    }
}
