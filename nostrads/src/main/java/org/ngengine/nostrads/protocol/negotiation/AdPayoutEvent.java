package org.ngengine.nostrads.protocol.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

public class AdPayoutEvent extends AdNegotiationEvent {
    AdOfferEvent offer;
 
    public AdPayoutEvent(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer,        Map<String, Object> content) {
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

    public static class PayoutBuilder extends AdNegotiationEvent.Builder<AdPayoutEvent> {
        private final static Factory<AdPayoutEvent> cstr = new Factory<AdPayoutEvent>() {
            @Override
            public AdPayoutEvent create(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer,
                    Map<String, Object> content) {
                return new AdPayoutEvent(signer, event, offer, content);
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
