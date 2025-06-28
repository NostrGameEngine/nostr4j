package org.ngengine.nostr4j.ads.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;

public class SdanAcceptOfferEvent extends SdanPowNegotiationEvent {

    public SdanAcceptOfferEvent(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer,
            Map<String, Object> content) {
        super( event, offer, content);
    }

    public static class SdanAcceptOfferBuilder extends SdanPowNegotiationEvent.PowBuilder<SdanAcceptOfferEvent> {
        private final static Factory<SdanAcceptOfferEvent> cstr = new Factory<SdanAcceptOfferEvent>() {
            @Override
            public SdanAcceptOfferEvent create(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer,
                    Map<String, Object> content) {
                return new SdanAcceptOfferEvent(signer, event, offer, content);
            }
        };
        public SdanAcceptOfferBuilder() {
            super(cstr, "accept_offer");
        }
    }

}
