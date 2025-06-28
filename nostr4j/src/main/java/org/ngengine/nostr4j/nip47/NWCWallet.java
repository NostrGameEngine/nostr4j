package org.ngengine.nostr4j.nip47;

import static org.ngengine.platform.NGEUtils.safeMSats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrWaitForEventFetchPolicy;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostr4j.utils.UniqueId;
import org.ngengine.nostr4j.wallet.NostrWallet;
import org.ngengine.nostr4j.wallet.info.NostrWalletBalance;
import org.ngengine.nostr4j.wallet.info.NostrWalletInfo;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletInvoice;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletInvoiceType;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletLookupInvoiceRequest;
import org.ngengine.nostr4j.wallet.invoice.NostrWalletMakeInvoiceRequest;
import org.ngengine.nostr4j.wallet.keysend.NostrWalletPayKeysendRequest;
import org.ngengine.nostr4j.wallet.keysend.NostrWalletPayKeysendResponse;
import org.ngengine.nostr4j.wallet.keysend.NostrWalletTLVRecord;
import org.ngengine.nostr4j.wallet.pay.NostrWalletPayRequest;
import org.ngengine.nostr4j.wallet.pay.NostrWalletPayResponse;
import org.ngengine.nostr4j.wallet.transactions.NostrWalletListTransactionsRequest;
import org.ngengine.nostr4j.wallet.transactions.NostrWalletTransaction;
import org.ngengine.nostr4j.wallet.transactions.NostrWalletTransactionType;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

public class NWCWallet implements NostrWallet{
    private Logger logger = Logger.getLogger(NWCWallet.class.getName());
    public static final int INFO_KIND = 13194;
    public static final int REQUEST_KIND = 23194;
    public static final int RESPONSE_KIND = 23195;
    public static final int NOTIFICATION_KIND = 23196;


    protected final NostrPool pool;
    protected final NWCUri uri;
    protected AsyncTask<List<String>> supportedMethods = null;

    public NWCWallet(NWCUri uri) {
        this(new NostrPool(), uri);
    }


    public NWCWallet(NostrPool pool, NWCUri uri) {
        this.pool = pool;
        for (String relay : uri.getRelays()) {
            this.pool.ensureRelay(relay);
        }
        this.uri = uri;
    }

    

 
    public AsyncTask<List<String>> getSupportedMethods(){
        if(supportedMethods == null){
            supportedMethods = pool.fetch(
                new NostrFilter()
                .withKind(INFO_KIND)
                .withAuthor(uri.getPubkey())
                .limit(1)
            ).then(evs->{
                if(evs.isEmpty()){
                    logger.warning("INFO event found for " + uri.getPubkey().asHex()+" will run with default capabilities");
                    return List.of(
                        "pay_invoice",
                        "make_invoice",
                        "lookup_invoice",
                        "list_transactions",
                        "get_balance"
                    );
                }
                SignedNostrEvent ev = evs.get(0);
                return Arrays.asList(ev.getContent().split(" "));
            });
        }
        return supportedMethods;
    }

    private AsyncTask<Map<String, Object>> waitForReply(String method, SignedNostrEvent ev) {
        NostrKeyPairSigner signer = uri.getSigner();

        return signer.getPublicKey().compose(pubkey->{   
            return pool.fetch(
                new NostrFilter()
                .withKind(NWCWallet.RESPONSE_KIND)
                .withAuthor(uri.getPubkey().asHex())
                .withTag("e", ev.getId())
                .withTag("p", pubkey.asHex()),
                NostrWaitForEventFetchPolicy.get(e->true)).then(r->{
                return r.get(0);
            });
        }).compose(response->{
            String content = response.getContent();
            return signer.decrypt(content, response.getPubkey(),
                    NostrSigner.EncryptAlgo.NIP04).then(decryptedContent ->{
                Map<String,Object> data = NGEPlatform.get().fromJSON(decryptedContent, Map.class);
                String resultType = (String) data.get("result_type");
                if(!resultType.equals(method)) throw new IllegalStateException("Unexpected result type: " + resultType + ", expected: " + method);
                Map<String, String> error = (Map<String, String>) data.get("error");
                if(error!=null){
                    String code = NGEUtils.safeString(error.get("code"));
                    String message = NGEUtils.safeString(error.get("message"));
                    throw new NWCException(code, message);
                }
                Map<String, Object> result = (Map<String, Object>) data.get("result");
                return result;
            });
            
        });     
    }

    private AsyncTask<Map<String, Object>> makeReq(
        String method, 
        Map<String, Object> params,
        @Nullable Instant expiresAt
    ) {
        return getSupportedMethods().compose(supported -> {
            if (!supported.contains(method)) {
                throw new IllegalArgumentException("Method " + method + " is not supported by this wallet");
            }
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
            logger.finer("Making request: " + req.toString());
            return signer.encrypt(json, uri.getPubkey(), NostrSigner.EncryptAlgo.NIP04).compose(encryptedJson -> {
                req.withContent(encryptedJson);
                return signer.sign(req);                
            }).compose(sev->{
                pool.publish(sev);
                AsyncTask<Map<String, Object>> res = waitForReply(method, sev);
                return res;
            });
        });
    }

    private Map<String,Object> mapOfNonNull(Object... keyValues) {
        Map<String,Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                map.put(key.toString(), value);
            }
        }
        return map;
    }

    private Map<String,Object> mapPayRequest(NostrWalletPayRequest req, boolean withId) {
        return mapOfNonNull(
            // "id", withId? (
            //     req.id()!=null ? NGEUtils.safeString(req.id()):UniqueId.getNext()
            // ) :null,
            "invoice", NGEUtils.safeString(Objects.requireNonNull(req.invoice()))
        );
    }

    private NostrWalletPayResponse mapPayResponses(Map<String,Object> res, boolean withId) {
        return new NostrWalletPayResponse(
                NGEUtils.safeString(Objects.requireNonNull(res.get("preimage"))),        
                res.containsKey("fees_paid") ? safeMSats(res.get("fees_paid")) : null,
                withId ? NGEUtils.safeString(res.get("id")) : null
        );
    }

    @Override
    public AsyncTask<NostrWalletPayResponse> pay(
        NostrWalletPayRequest req,
        @Nullable Instant expiresAt
    ) {
        return makeReq(
            "pay_invoice",
            mapPayRequest(req, false),
            expiresAt
        ).then(res->{
            return  mapPayResponses(res, false);
        });
    }

   

    @Override
    public AsyncTask<NostrWalletPayKeysendResponse> keysend(
        NostrWalletPayKeysendRequest req,
        @Nullable Instant expiresAt
    ) {
        Map<String,Object> tlvRecordsMap=null;
        List<NostrWalletTLVRecord> tlvRecords = req.tlvRecords();
        if(tlvRecords!=null){
            for (NostrWalletTLVRecord record : tlvRecords) {
                if(tlvRecordsMap == null) {
                    tlvRecordsMap = new HashMap<>();
                }
                tlvRecordsMap.put("type", NGEUtils.safeLong(record.type()));
                tlvRecordsMap.put("value", NGEUtils.safeString(Objects.requireNonNull(record.value())));
            }
        }

        return makeReq(
            "pay_keysend",
            mapOfNonNull(
                "pubkey", NGEUtils.safeString(Objects.requireNonNull(req.pubkey())),
                "amount", NGEUtils.safeMSats(req.amountMsats()),
                "pubkey", NGEUtils.safeString(Objects.requireNonNull(req.pubkey())),
                "preimage", req.preimage() != null ? NGEUtils.safeString(req.preimage()) : null,
                "tlv_records", tlvRecordsMap
            ),
            expiresAt
        ).then(res->{
            return new NostrWalletPayKeysendResponse(
                NGEUtils.safeString(Objects.requireNonNull(res.get("preimage"))),
                res.containsKey("fees_paid") ? NGEUtils.safeMSats(res.get("fees_paid")) : null
            );
        });
    }

  

    @Override
    public AsyncTask<NostrWalletInvoice> invoice(
        NostrWalletMakeInvoiceRequest req,
        @Nullable Instant expiresAt
    ) {
        return makeReq(
                "make_invoice",
                mapOfNonNull(
                        "amount",  NGEUtils.safeMSats(req.amountMsats()),
                        "description", req.description() !=null ? NGEUtils.safeString(req.description()) : null,
                        "description_hash", req.descriptionHash() != null ? NGEUtils.safeString(req.descriptionHash()) : null,
                        "expiry", req.expiry() != null ? NGEUtils.safeDurationInSeconds(req.expiry()) : null
                ),
                expiresAt)
                .then(res -> { 
                    return new NostrWalletInvoice(
                        NostrWalletInvoiceType.valueOf(Objects.requireNonNull(res.get("type")).toString()),
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
                        (Map<String,Object>)res.get("metadata")
                    );
                });
    }

    @Override
    public AsyncTask<NostrWalletInvoice> lookup(
        NostrWalletLookupInvoiceRequest req,
        @Nullable Instant expiresAt
    ) {
        String paymentHash =  req.paymentHash() != null ? NGEUtils.safeString(req.paymentHash()) : null;
        String invoice = req.invoice() != null ? NGEUtils.safeString(req.invoice()) : null;
        if (paymentHash == null && invoice == null) {
            throw new IllegalArgumentException("Either paymentHash or invoice must be provided");
        }
        return makeReq(
            "lookup_invoice",
            mapOfNonNull(
                    "payment_hash", paymentHash,
                    "invoice", invoice
            ),
            expiresAt
        )
            .then(res -> {
                return new NostrWalletInvoice(
                        NostrWalletInvoiceType.valueOf(Objects.requireNonNull(res.get("type")).toString()),
                        res.containsKey("invoice") ? NGEUtils.safeString(res.get("invoice")) : null,
                        res.containsKey("description") ? NGEUtils.safeString(res.get("description")) : null,
                        res.containsKey("description_hash") ? NGEUtils.safeString(res.get("description_hash"))
                                : null,
                        res.containsKey("preimage") ? NGEUtils.safeString(res.get("preimage")) : null,
                        NGEUtils.safeString(Objects.requireNonNull(res.get("payment_hash"))),
                        NGEUtils.safeMSats(res.get("amount")),
                        NGEUtils.safeMSats(res.get("fees_paid")),
                        NGEUtils.safeInstantInSeconds(Objects.requireNonNull(res.get("created_at"))),
                        res.containsKey("expires_at") ? NGEUtils.safeInstantInSeconds(res.get("expires_at")) : null,
                        res.containsKey("settled_at") ? NGEUtils.safeInstantInSeconds(res.get("settled_at")) : null,
                        (Map<String, Object>) res.get("metadata"));
            });
    }

    @Override
    public AsyncTask<List<NostrWalletTransaction>> listTransactions(
        NostrWalletListTransactionsRequest req,
        @Nullable Instant expiresAt        
    ) {
        return makeReq(
                "list_transactions",
                mapOfNonNull(
                    "from", req.from() != null ? NGEUtils.safeInstantInSeconds(req.from()).getEpochSecond() : 0,
                    "until", req.until() != null ? NGEUtils.safeInstantInSeconds(req.until()).getEpochSecond() : Instant.now().getEpochSecond(),
                    "limit", req.limit() != null ? NGEUtils.safeInt(req.limit()) : null,
                    "offset", req.offset() != null ? NGEUtils.safeInt(req.offset()) : null,
                    "unpaid", req.includeUnpaid(),
                    "type", req.type() != null ? NostrWalletTransactionType.valueOf(req.type().name()).toString() : null

                ), expiresAt)
                .then(resA -> {
                    List<NostrWalletTransaction> out = new ArrayList<>();
                    Collection<Map<String,Object>> transactions = (Collection<Map<String, Object>>) resA.get("transactions");
                    for(Map<String,Object> res: transactions){
                        NostrWalletTransaction tr= new NostrWalletTransaction(
                                NostrWalletTransactionType.valueOf(Objects.requireNonNull(res.get("type")).toString()),
                                res.containsKey("invoice") ? NGEUtils.safeString(res.get("invoice")) : null,
                                res.containsKey("description") ? NGEUtils.safeString(res.get("description")) : null,
                                res.containsKey("description_hash") ? NGEUtils.safeString(res.get("description_hash"))
                                        : null,
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
    public AsyncTask<NostrWalletBalance> getBalance(@Nullable Instant expiresAt    ) {
        return makeReq(
                "get_balance",
                mapOfNonNull(
                       
                ),expiresAt)
                .then(resA -> {
                    NostrWalletBalance out = new NostrWalletBalance(
                        NGEUtils.safeMSats(resA.get("balance"))                      
                    );                    

                    return out;
                });
    }

    @Override
    public AsyncTask<NostrWalletInfo> getInfo(@Nullable Instant expiresAt    ) {
        return makeReq(
                "get_info",
                mapOfNonNull(

                ),expiresAt)
                .then(resA -> {
                    NostrWalletInfo out = new NostrWalletInfo(
                        resA.containsKey("alias") ? NGEUtils.safeString(resA.get("alias")) : null,
                        resA.containsKey("color") ? NGEUtils.safeString(resA.get("color")) : null,
                        resA.containsKey("pubkey") ?NGEUtils.safeString(resA.get("pubkey")): null,
                        resA.containsKey("network") ? NGEUtils.safeString(resA.get("network")) : null,
                        resA.containsKey("block_height") ? NGEUtils.safeInt(resA.get("block_height")) : null,
                        resA.containsKey("block_hash") ? NGEUtils.safeString(resA.get("block_hash")) : null,
                        resA.containsKey("methods") ? NGEUtils.safeStringList(resA.get("methods")) : List.of(),
                        resA.containsKey("notifications") ? NGEUtils.safeStringList(resA.get("notifications")) : List.of()               
                      
                    );

                    return out;
                });
    }
    
}
