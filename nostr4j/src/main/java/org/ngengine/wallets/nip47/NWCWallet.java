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

package org.ngengine.wallets.nip47;

import static org.ngengine.platform.NGEUtils.safeMSats;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrWaitForEventFetchPolicy;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.wallets.InvoiceData;
import org.ngengine.wallets.InvoiceProperties;
import org.ngengine.wallets.InvoiceType;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.TransactionInfo;
import org.ngengine.wallets.TransactionType;
import org.ngengine.wallets.Wallet;
import org.ngengine.wallets.WalletInfo;
import org.ngengine.wallets.nip47.keysend.NWCKeysendResponse;
import org.ngengine.wallets.nip47.keysend.NWCTLVRecord;

public class NWCWallet implements Wallet {

    private Logger logger = Logger.getLogger(NWCWallet.class.getName());
    public static final int INFO_KIND = 13194;
    public static final int REQUEST_KIND = 23194;
    public static final int RESPONSE_KIND = 23195;
    public static final int NOTIFICATION_KIND = 23196;

    protected final NostrPool pool;
    protected final NWCUri uri;
    protected AsyncTask<List<String>> supportedMethods = null;
    protected boolean tempPool = false;
    protected Runnable closer;

    public NWCWallet(NWCUri uri) {
        this(null, uri);
    }

    public NWCWallet(NostrPool pool, NWCUri uri) {
        if (pool == null) {
            // if pool is not provided we create a new one
            // and we register it to be closed when this wallet is released  by the gc
            NostrPool newPool = new NostrPool();
            closer =
                NGEPlatform
                    .get()
                    .registerFinalizer(
                        this,
                        () -> {
                            newPool.close();
                        }
                    );
            this.pool = newPool;
        } else {
            this.pool = pool;
        }

        for (String relay : uri.getRelays()) {
            this.pool.ensureRelay(relay);
        }
        this.uri = uri;
    }

    @Override
    public void close() {
        if (closer != null) {
            closer.run();
            closer = null;
        }
    }

    public AsyncTask<List<String>> getSupportedMethods() {
        logger.finest("Fetching supported methods for wallet: " + uri);
        if (supportedMethods == null) {
            supportedMethods =
                pool
                    .fetch(new NostrFilter().withKind(INFO_KIND).withAuthor(uri.getPubkey()).limit(1))
                    .then(evs -> {
                        if (evs.isEmpty()) {
                            logger.warning(
                                "INFO event found for " + uri.getPubkey().asHex() + " will run with default capabilities"
                            );
                            return List.of("pay_invoice", "make_invoice", "lookup_invoice", "list_transactions", "get_balance");
                        }
                        SignedNostrEvent ev = evs.get(0);
                        return Arrays.asList(ev.getContent().split(" "));
                    });
        }
        logger.finest("Supported methods: " + supportedMethods);
        return supportedMethods;
    }

    private AsyncTask<Map<String, Object>> waitForReply(String method, SignedNostrEvent ev) {
        NostrKeyPairSigner signer = uri.getSigner();

        return signer
            .getPublicKey()
            .compose(pubkey -> {
                return pool
                    .fetch(
                        new NostrFilter()
                            .withKind(NWCWallet.RESPONSE_KIND)
                            .withAuthor(uri.getPubkey().asHex())
                            .withTag("e", ev.getId())
                            .withTag("p", pubkey.asHex()),
                        NostrWaitForEventFetchPolicy.get(e -> true, 1, false)
                    )
                    .then(r -> {
                        return r.get(0);
                    });
            })
            .compose(response -> {
                String content = response.getContent();
                return signer
                    .decrypt(content, response.getPubkey(), NostrSigner.EncryptAlgo.NIP04)
                    .then(decryptedContent -> {
                        logger.finest("Receiving response: " + decryptedContent);
                        Map<String, Object> data = NGEPlatform.get().fromJSON(decryptedContent, Map.class);
                        String resultType = (String) data.get("result_type");
                        if (!resultType.equals(method)) throw new IllegalStateException(
                            "Unexpected result type: " + resultType + ", expected: " + method
                        );
                        Map<String, String> error = (Map<String, String>) data.get("error");
                        if (error != null) {
                            String code = NGEUtils.safeString(error.get("code"));
                            String message = NGEUtils.safeString(error.get("message"));
                            logger.log(Level.FINE, error.toString() + " | code: " + code + ", message: " + message);
                            throw new NWCException(code, message);
                        }
                        Map<String, Object> result = (Map<String, Object>) data.get("result");
                        result = evictNulls(result);
                        return result;
                    });
            });
    }

    private AsyncTask<Map<String, Object>> makeReq(String method, Map<String, Object> params, @Nullable Instant expiresAt) {
        return getSupportedMethods()
            .compose(supported -> {
                if (!supported.contains(method)) {
                    throw new IllegalArgumentException("Method " + method + " is not supported by this wallet");
                }
                logger.finest("Making request " + method + " with params: " + params + (expiresAt != null ? " expiring at " + expiresAt : ""));
                Map<String, Object> content = new HashMap<>();
                content.put("method", method);
                content.put("params", params);
                String json = NGEPlatform.get().toJSON(content);
                NostrKeyPairSigner signer = uri.getSigner();
                UnsignedNostrEvent req = new UnsignedNostrEvent();
                req.withContent(json);
                req.withKind(REQUEST_KIND);
                req.withTag("p", uri.getPubkey().asHex());
                if (expiresAt != null) {
                    req.withExpiration(expiresAt);
                }
                logger.finest("Making request: " + req.toString());
                return signer
                    .encrypt(json, uri.getPubkey(), NostrSigner.EncryptAlgo.NIP04)
                    .compose(encryptedJson -> {
                        req.withContent(encryptedJson);
                        return signer.sign(req);
                    })
                    .compose(sev -> {
                        logger.finest("Sending request event: " + sev);
                        pool.publish(sev);
                        logger.finest("Request event sent, waiting for reply...");
                        AsyncTask<Map<String, Object>> res = waitForReply(method, sev);
                        return res;
                    });
            });
    }

    private Map<String, Object> mapOfNonNull(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                map.put(key.toString(), value);
            }
        }
        return map;
    }

    private Map<String, Object> evictNulls(Map<String, Object> map) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private PayResponse mapPayResponses(Map<String, Object> res, boolean withId) {
        return new PayResponse(
            NGEUtils.safeString(Objects.requireNonNull(res.get("preimage"))),
            res.containsKey("fees_paid") ? safeMSats(res.get("fees_paid")) : null,
            withId ? NGEUtils.safeString(res.get("id")) : null
        );
    }

    @Override
    public AsyncTask<PayResponse> payInvoice(
        @Nonnull String invoice,
        @Nullable Long amountMsats,
        @Nullable Instant expireRequestAt
    ) {
        return makeReq(
            "pay_invoice",
            mapOfNonNull(
                "invoice",
                NGEUtils.safeString(Objects.requireNonNull(invoice)),
                "amount",
                amountMsats != null ? NGEUtils.safeMSats(amountMsats) : null
            ),
            expireRequestAt
        )
            .then(res -> {
                logger.finest("Pay request sent, got response: " + res);
                return mapPayResponses(res, false);
            });
    }

    public AsyncTask<NWCKeysendResponse> keySend(
        @Nullable String id,
        long amountMsats,
        @Nonnull String pubkey,
        @Nullable String preimage,
        @Nullable List<NWCTLVRecord> tlvRecords,
        @Nullable Instant expireRequestAt
    ) {
        Map<String, Object> tlvRecordsMap = null;
        if (tlvRecords != null) {
            for (NWCTLVRecord record : tlvRecords) {
                if (tlvRecordsMap == null) {
                    tlvRecordsMap = new HashMap<>();
                }
                tlvRecordsMap.put("type", NGEUtils.safeLong(record.type()));
                tlvRecordsMap.put("value", NGEUtils.safeString(Objects.requireNonNull(record.value())));
            }
        }

        return makeReq(
            "pay_keysend",
            mapOfNonNull(
                "pubkey",
                NGEUtils.safeString(Objects.requireNonNull(pubkey)),
                "amount",
                NGEUtils.safeMSats(amountMsats),
                "pubkey",
                NGEUtils.safeString(Objects.requireNonNull(pubkey)),
                "preimage",
                preimage != null ? NGEUtils.safeString(preimage) : null,
                "tlv_records",
                tlvRecordsMap
            ),
            expireRequestAt
        )
            .then(res -> {
                return new NWCKeysendResponse(
                    NGEUtils.safeString(Objects.requireNonNull(res.get("preimage"))),
                    res.containsKey("fees_paid") ? NGEUtils.safeMSats(res.get("fees_paid")) : null
                );
            });
    }

    @Override
    public AsyncTask<InvoiceData> makeInvoice(InvoiceProperties req, @Nullable Instant expireRequestAt) {
        return makeReq(
            "make_invoice",
            mapOfNonNull(
                "amount",
                NGEUtils.safeMSats(req.amountMsats()),
                "description",
                req.description() != null ? NGEUtils.safeString(req.description()) : null,
                "description_hash",
                req.descriptionHash() != null ? NGEUtils.safeString(req.descriptionHash()) : null,
                "expiry",
                req.expiry() != null ? NGEUtils.safeDurationInSeconds(req.expiry()) : null
            ),
            expireRequestAt
        )
            .then(res -> {
                return new InvoiceData(
                    InvoiceType.valueOf(Objects.requireNonNull(res.get("type")).toString()),
                    res.containsKey("invoice") ? NGEUtils.safeString(res.get("invoice")) : null,
                    res.containsKey("description") ? NGEUtils.safeString(res.get("description")) : null,
                    res.containsKey("description_hash") ? NGEUtils.safeString(res.get("description_hash")) : null,
                    res.containsKey("preimage") ? NGEUtils.safeString(res.get("preimage")) : null,
                    NGEUtils.safeString(Objects.requireNonNull(res.get("payment_hash"))),
                    NGEUtils.safeMSats(res.get("amount")),
                    NGEUtils.safeMSats(res.get("fees_paid")),
                    NGEUtils.safeInstantInSeconds(Objects.requireNonNull(res.get("created_at"))),
                    res.containsKey("expires_at") ? NGEUtils.safeInstantInSeconds(res.get("expires_at")) : null,
                    null,
                    (Map<String, Object>) res.get("metadata")
                );
            });
    }

    @Override
    public AsyncTask<InvoiceData> lookupInvoice(
        @Nullable String paymentHash,
        @Nullable String invoice,
        @Nullable Instant expireRequestAt
    ) {
        if (paymentHash == null && invoice == null) {
            throw new IllegalArgumentException("Either paymentHash or invoice must be provided");
        }
        return makeReq(
            "lookup_invoice",
            mapOfNonNull(
                "payment_hash",
                paymentHash != null ? NGEUtils.safeString(paymentHash) : null,
                "invoice",
                invoice != null ? NGEUtils.safeString(invoice) : null
            ),
            expireRequestAt
        )
            .then(res -> {
                return new InvoiceData(
                    InvoiceType.valueOf(Objects.requireNonNull(res.get("type")).toString()),
                    res.containsKey("invoice") ? NGEUtils.safeString(res.get("invoice")) : null,
                    res.containsKey("description") ? NGEUtils.safeString(res.get("description")) : null,
                    res.containsKey("description_hash") ? NGEUtils.safeString(res.get("description_hash")) : null,
                    res.containsKey("preimage") ? NGEUtils.safeString(res.get("preimage")) : null,
                    NGEUtils.safeString(Objects.requireNonNull(res.get("payment_hash"))),
                    NGEUtils.safeMSats(res.get("amount")),
                    NGEUtils.safeMSats(res.get("fees_paid")),
                    NGEUtils.safeInstantInSeconds(Objects.requireNonNull(res.get("created_at"))),
                    res.containsKey("expires_at") ? NGEUtils.safeInstantInSeconds(res.get("expires_at")) : null,
                    res.containsKey("settled_at") ? NGEUtils.safeInstantInSeconds(res.get("settled_at")) : null,
                    (Map<String, Object>) res.get("metadata")
                );
            });
    }

    @Override
    public AsyncTask<List<TransactionInfo>> listTransactions(
        @Nullable Instant from,
        @Nullable Instant until,
        @Nullable Integer limit,
        @Nullable Integer offset,
        boolean includeUnpaid,
        @Nullable TransactionType type,
        @Nullable Instant expireRequestAt
    ) {
        return makeReq(
            "list_transactions",
            mapOfNonNull(
                "from",
                from != null ? NGEUtils.safeInstantInSeconds(from).getEpochSecond() : 0,
                "until",
                until != null ? NGEUtils.safeInstantInSeconds(until).getEpochSecond() : Instant.now().getEpochSecond(),
                "limit",
                limit != null ? NGEUtils.safeInt(limit) : null,
                "offset",
                offset != null ? NGEUtils.safeInt(offset) : null,
                "unpaid",
                includeUnpaid,
                "type",
                type != null ? TransactionType.valueOf(type.name()).toString() : null
            ),
            expireRequestAt
        )
            .then(resA -> {
                List<TransactionInfo> out = new ArrayList<>();
                Collection<Map<String, Object>> transactions = (Collection<Map<String, Object>>) resA.get("transactions");
                for (Map<String, Object> res : transactions) {
                    TransactionInfo tr = new TransactionInfo(
                        TransactionType.valueOf(Objects.requireNonNull(res.get("type")).toString()),
                        res.containsKey("invoice") ? NGEUtils.safeString(res.get("invoice")) : null,
                        res.containsKey("description") ? NGEUtils.safeString(res.get("description")) : null,
                        res.containsKey("description_hash") ? NGEUtils.safeString(res.get("description_hash")) : null,
                        res.containsKey("preimage") ? NGEUtils.safeString(res.get("preimage")) : null,
                        NGEUtils.safeString(Objects.requireNonNull(res.get("payment_hash"))),
                        NGEUtils.safeMSats(res.get("amount")),
                        NGEUtils.safeMSats(res.get("fees_paid")),
                        NGEUtils.safeInstantInSeconds(Objects.requireNonNull(res.get("created_at"))),
                        res.containsKey("expires_at") ? NGEUtils.safeInstantInSeconds(res.get("expires_at")) : null,
                        res.containsKey("settled_at") ? NGEUtils.safeInstantInSeconds(res.get("settled_at")) : null,
                        (Map<String, Object>) res.get("metadata")
                    );
                    out.add(tr);
                }

                return out;
            });
    }

    @Override
    public AsyncTask<Long> getBalance(@Nullable Instant expireRequestAt) {
        return makeReq("get_balance", mapOfNonNull(), expireRequestAt)
            .then(resA -> {
                return NGEUtils.safeMSats(Objects.requireNonNull(resA.get("balance")));
            });
    }

    @Override
    public AsyncTask<WalletInfo> getInfo(@Nullable Instant expireRequestAt) {
        return makeReq("get_info", mapOfNonNull(), expireRequestAt)
            .then(resA -> {
                WalletInfo out = new WalletInfo(
                    resA.containsKey("alias") ? NGEUtils.safeString(resA.get("alias")) : null,
                    resA.containsKey("color") ? NGEUtils.safeString(resA.get("color")) : null,
                    resA.containsKey("pubkey") ? NGEUtils.safeString(resA.get("pubkey")) : null,
                    resA.containsKey("network") ? NGEUtils.safeString(resA.get("network")) : null,
                    resA.containsKey("block_height") ? NGEUtils.safeInt(resA.get("block_height")) : null,
                    resA.containsKey("block_hash") ? NGEUtils.safeString(resA.get("block_hash")) : null,
                    resA.containsKey("methods") ? NGEUtils.safeStringList(resA.get("methods")) : List.of(),
                    resA.containsKey("notifications") ? NGEUtils.safeStringList(resA.get("notifications")) : List.of()
                );

                return out;
            });
    }

    @Override
    public boolean isReady() {
        return true; // nwc is born ready
    }

    @Override
    public AsyncTask<Boolean> waitForReady() {
        return NGEPlatform
            .get()
            .wrapPromise((res, rej) -> {
                res.accept(true); // nwc does not wait
            });
    }

    @Override
    public AsyncTask<Boolean> isMethodSupported(Methods method) {
        switch (method) {
            case payInvoice:
                return getSupportedMethods()
                    .then(supported -> {
                        return supported.contains("pay_invoice");
                    });
            case makeInvoice:
                return getSupportedMethods()
                    .then(supported -> {
                        return supported.contains("make_invoice");
                    });
            case lookupInvoice:
                return getSupportedMethods()
                    .then(supported -> {
                        return supported.contains("lookup_invoice");
                    });
            case listTransactions:
                return getSupportedMethods()
                    .then(supported -> {
                        return supported.contains("list_transactions");
                    });
            case getBalance:
                return getSupportedMethods()
                    .then(supported -> {
                        return supported.contains("get_balance");
                    });
            case getInfo:
                return getSupportedMethods()
                    .then(supported -> {
                        return supported.contains("get_info");
                    });
            default:
                return NGEPlatform
                    .get()
                    .wrapPromise((res, rej) -> {
                        res.accept(false);
                    });
        }
    }
}
