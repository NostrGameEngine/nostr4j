package org.ngengine.nostr4j.ads.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

public class SdanPayoutEvent extends SdanNegotiationEvent {
    SdanOfferEvent offer;
 
    public SdanPayoutEvent(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer,        Map<String, Object> content) {
        super( event, offer,content);
    }

    @Override
    public void checkValid() throws Exception {
        super.checkValid();
        Object msg = getMessage();
        if (msg == null) {
            throw new Exception("Payout event must have a message");
        }
        getPreimage();
    }

    @Nullable
    public String getPreimage(){
        Object preimage = getData("preimage", true);
        return preimage != null ? NGEUtils.safeString(preimage) : null;
    }

    public static class PayoutBuilder extends SdanNegotiationEvent.Builder<SdanPayoutEvent> {
        private final static Factory<SdanPayoutEvent> cstr = new Factory<SdanPayoutEvent>() {
            @Override
            public SdanPayoutEvent create(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer,
                    Map<String, Object> content) {
                return new SdanPayoutEvent(signer, event, offer, content);
            }
        };

        public PayoutBuilder() {
            super(cstr, "payout");
        }

        public PayoutBuilder withPreimage(String preimage) {
            this.content.put("preimage", preimage);
            return this;
        }

    
    }

 
}
