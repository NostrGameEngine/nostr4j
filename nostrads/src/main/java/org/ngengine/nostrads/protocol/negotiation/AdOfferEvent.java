package org.ngengine.nostrads.protocol.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.NGEUtils;

public class AdOfferEvent extends AdPowNegotiationEvent {
 
   
    public AdOfferEvent(NostrSigner signer, SignedNostrEvent event, Map<String, Object> content) {
        super(event, null,content);
    }

    @Override
    public AdOfferEvent getOffer() {
        return this;     
    }

    public NostrPublicKey getAppPubkey() {
        return NostrPublicKey.fromHex(NGEUtils.safeString(getTagData("y", true)));
    }

    @Override
    public void checkValid() throws Exception {
        super.checkValid();
        getAppPubkey();
    }

    public static  class OfferBuilder extends AdPowNegotiationEvent.PowBuilder<AdOfferEvent> {
        private final static Factory<AdOfferEvent> cstr = new Factory<AdOfferEvent>() {
            @Override
            public AdOfferEvent create(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer,
                    Map<String, Object> content) {
                return new AdOfferEvent(signer, event, content);
            }
        };
        public OfferBuilder(NostrPublicKey appPubkey) {
            super(cstr, "offer");      
            event.replaceTag("y", appPubkey.asHex());
        }

         
    }


}
