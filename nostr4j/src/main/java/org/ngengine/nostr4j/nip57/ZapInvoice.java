package org.ngengine.nostr4j.nip57;

import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.NGEUtils;

public final class ZapInvoice {
    private final String invoice;
    private final String comment;
    private final ZapRequest zapRequest;
    private final NostrPublicKey providerPubkey;


    public ZapInvoice(String invoice,  String comment, SignedNostrEvent zapRequest, NostrPublicKey providerPubkey) {
        this.invoice = Objects.requireNonNull(invoice, "invoice");
        if (this.invoice.isEmpty()) {
            throw new IllegalArgumentException("invoice must not be empty");
        }
        this.comment = comment == null ? "" : comment;
        Objects.requireNonNull(zapRequest, "zapRequest");
        this.zapRequest = zapRequest instanceof ZapRequest ? (ZapRequest) zapRequest : new ZapRequest(zapRequest.toMap());
        this.providerPubkey = Objects.requireNonNull(providerPubkey, "providerPubkey");
    }

    @SuppressWarnings("unchecked")
    public ZapInvoice(Map<String, Object> map) throws URISyntaxException {
        Objects.requireNonNull(map, "map");
        this.invoice = NGEUtils.safeString(map.get("invoice"));
        if (this.invoice.isEmpty()) {
            throw new IllegalArgumentException("invoice must not be empty");
        }
        this.comment = NGEUtils.safeString(map.get("comment"));
        Map<String, Object> zapRequestMap = (Map<String, Object>) map.get("request");
        Objects.requireNonNull(zapRequestMap, "request");
        this.zapRequest = new ZapRequest(zapRequestMap);
        String providerPubkeyHex = NGEUtils.safeString(map.get("provider_pubkey"));
        if (providerPubkeyHex.isEmpty()) {
            throw new IllegalArgumentException("provider_pubkey must not be empty");
        }
        this.providerPubkey =  NostrPublicKey.fromHex(providerPubkeyHex);
    }

    public NostrPublicKey getProviderPubkey() {
        return providerPubkey;
    }

    public String getComment() {
        return comment;
    }

    public ZapRequest getZapRequest() {
        return zapRequest;
    }

    public String getInvoice() {
        return invoice;
    }


    @Override
    public String toString() {
        return invoice;
    }

    @Override
    public boolean equals(Object o) {            
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ZapInvoice that = (ZapInvoice) o;
        return invoice.equals(that.invoice) 
        && zapRequest.equals(that.zapRequest) && providerPubkey.equals(that.providerPubkey);
    }

    @Override
    public int hashCode() {
        int result = invoice.hashCode();
        result = 31 * result + zapRequest.hashCode();
        result = 31 * result + providerPubkey.hashCode();
        return result;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = zapRequest.toMap();
        map.put("invoice", invoice);
        map.put("comment", comment);
        map.put("request", zapRequest.toMap());
        map.put("provider_pubkey", providerPubkey.asHex());
        return map;
    }
}