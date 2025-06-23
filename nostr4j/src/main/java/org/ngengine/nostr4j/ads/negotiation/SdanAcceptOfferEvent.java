package org.ngengine.nostr4j.ads.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;

public class SdanAcceptOfferEvent extends SdanPowNegotiationEvent {

    public SdanAcceptOfferEvent(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer,
            Map<String, Object> content) {
        super( event, offer, content);
    }

    public static class Builder extends SdanPowNegotiationEvent.PowBuilder<SdanAcceptOfferEvent> {
        public Builder() {
            super((signer, event, of, cc) -> new SdanAcceptOfferEvent(signer, event, of, cc), "accept_offer");
        }
    }

}
