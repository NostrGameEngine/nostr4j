package org.ngengine.nostr4j.ads.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;
public class SdanBailEvent extends SdanNegotiationEvent {
    public static enum Reason {
        OUT_OF_BUDGET("out_of_budget"),
        PAYMENT_METHOD_NOT_SUPPORTED("payment_method_not_supported"),
        INVOICE_EXPIRED_OR_INVALID("invoice_expired_or_invalid"),
        EXPIRED("expired"),
        NO_ROUTE("no_route"),
        ACTION_INCOMPLETE("action_incomplete"),
        CANCELLED("cancelled"),
        ADSPACE_UNAVAILABLE("adspace_unavailable"),
        AD_NOT_DISPLAYED("ad_not_displayed"),
        UNKNOWN("unknown");

        private final String reason;

        Reason(String reason) {
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return reason;
        }

        public static Reason fromString(String reason) {
            for (Reason r : Reason.values()) {
                if (r.getReason().equals(reason)) {
                    return r;
                }
            }
            return UNKNOWN;
        }
    }

 
    public SdanBailEvent(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer, Map<String, Object> content) {
        super( event, offer, content);
    }

    public Reason getReason() {
        String message = getMessage();
        return Reason.fromString(message);
    }

    @Override
    public void checkValid() throws Exception {
        super.checkValid();
        Object msg = getMessage();
        if (msg == null) {
            throw new Exception("Bail event must have a reason message");
        }
    }

    public static class SdanBailBuilder extends SdanNegotiationEvent.Builder<SdanBailEvent> {
        private final static Factory<SdanBailEvent> cstr = new Factory<SdanBailEvent>() {
            @Override
            public SdanBailEvent create(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer,
                    Map<String, Object> content) {
                return new SdanBailEvent(signer, event, offer, content);
            }
        };

        public SdanBailBuilder() {
            super(cstr, "bail");
        }
    

        public SdanBailBuilder withReason(Reason reason) {
            withMessage(reason.getReason());
            return this;
        }
       
    }

    
}
