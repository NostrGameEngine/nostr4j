package org.ngengine.nostrads.protocol.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;

public class AdPaymentRequestEvent extends AdPowNegotiationEvent {
   
    public AdPaymentRequestEvent(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer, Map<String, Object> content) {
        super( event, offer,content);
    }

    @Override
    public void checkValid() throws Exception {
        super.checkValid();
        Object msg = getMessage();
        if(msg==null){
            throw new Exception("Payment request must have a message");
        }
    }

    public static class PaymentRequestBuilder extends AdPowNegotiationEvent.PowBuilder<AdPaymentRequestEvent> {
        private final static Factory<AdPaymentRequestEvent> cstr = new Factory<AdPaymentRequestEvent>() {
            @Override
            public AdPaymentRequestEvent create(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer,
                    Map<String, Object> content) {
                return new AdPaymentRequestEvent(signer, event, offer, content);
            }
        };

        public PaymentRequestBuilder( ) {
            super(cstr, "payment_request");            
        } 

    }


}
