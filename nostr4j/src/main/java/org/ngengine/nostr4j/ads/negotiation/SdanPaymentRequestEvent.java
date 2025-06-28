package org.ngengine.nostr4j.ads.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;

public class SdanPaymentRequestEvent extends SdanNegotiationEvent {
   
    public SdanPaymentRequestEvent(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer, Map<String, Object> content) {
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

    public static class PaymentRequestBuilder extends SdanNegotiationEvent.Builder<SdanPaymentRequestEvent> {
        private final static Factory<SdanPaymentRequestEvent> cstr = new Factory<SdanPaymentRequestEvent>() {
            @Override
            public SdanPaymentRequestEvent create(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer,
                    Map<String, Object> content) {
                return new SdanPaymentRequestEvent(signer, event, offer, content);
            }
        };

        public PaymentRequestBuilder( ) {
            super(cstr, "payment_request");            
        } 

    }


}
