package org.ngengine.nostrads.protocol.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;

public class AdAcceptOfferEvent extends AdPowNegotiationEvent {

    public AdAcceptOfferEvent(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer,
            Map<String, Object> content) {
        super( event, offer, content);
    }

    public static class AdAcceptOfferBuilder extends AdPowNegotiationEvent.PowBuilder<AdAcceptOfferEvent> {
        private final static Factory<AdAcceptOfferEvent> cstr = new Factory<AdAcceptOfferEvent>() {
            @Override
            public AdAcceptOfferEvent create(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer,
                    Map<String, Object> content) {
                return new AdAcceptOfferEvent(signer, event, offer, content);
            }
        };
        public AdAcceptOfferBuilder() {
            super(cstr, "accept_offer");
        }
    }

}
