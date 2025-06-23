package org.ngengine.nostr4j.ads.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.NGEUtils;

public class SdanOfferEvent extends SdanPowNegotiationEvent {
 
   
    public SdanOfferEvent(NostrSigner signer, SignedNostrEvent event, Map<String, Object> content) {
        super(event, null,content);
    }

    @Override
    public SdanOfferEvent getOffer() {
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

    public static class Builder extends SdanPowNegotiationEvent.PowBuilder<SdanOfferEvent> {
        public Builder(NostrPublicKey appPubkey) {
            super((signer,event, offer,c )->new SdanOfferEvent(signer,event,c), "offer");      
            event.replaceTag("y", appPubkey.asHex());
        }

         
    }


}
