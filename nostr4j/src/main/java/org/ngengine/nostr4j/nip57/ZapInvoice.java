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

    public ZapInvoice(String invoice, String comment, SignedNostrEvent zapRequest, NostrPublicKey providerPubkey) {
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
        this.providerPubkey = NostrPublicKey.fromHex(providerPubkeyHex);
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
        return invoice.equals(that.invoice) && zapRequest.equals(that.zapRequest) && providerPubkey.equals(that.providerPubkey);
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
