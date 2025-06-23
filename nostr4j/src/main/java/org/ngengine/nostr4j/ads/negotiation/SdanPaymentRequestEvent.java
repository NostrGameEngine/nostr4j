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

    public static class Builder extends SdanNegotiationEvent.Builder<SdanPaymentRequestEvent> {
        public Builder( ) {
            super((signer,event, of,c)->new SdanPaymentRequestEvent(signer,event, of,c), "payment_request");            
        } 

    }


}
