package org.ngengine.nostr4j.ads.negotiation;

import java.util.Map;

import org.ngengine.nostr4j.ads.SdanBidEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * A negotiation event that requires proof of work (PoW) to respond.
 */
public class SdanPowNegotiationEvent extends SdanNegotiationEvent{
    public SdanPowNegotiationEvent(
        SignedNostrEvent event, 
        SdanOfferEvent offer,
        Map<String, Object> content
    ){
        super(event, offer, content);
    }
    
    public int getRequestedDifficultyToRespond() {
        Object difficulty = getData("difficulty", false);
        return difficulty != null ? NGEUtils.safeInt(difficulty) : 0;
    }

    @Override
    public void checkValid() throws Exception {
        super.checkValid();
        getRequestedDifficultyToRespond();
    }

    public static abstract class PowBuilder<T extends SdanNegotiationEvent> extends SdanNegotiationEvent.Builder<T> {

        public PowBuilder(
                Factory<T> factory,
                String type) {
            super(factory, type);
        }

        public PowBuilder<T> requestDifficulty(int difficulty) {
            content.put("difficulty", difficulty);
            return this;
        }

        public AsyncTask<T> build(
                NostrSigner signer,
                SignedNostrEvent negotiationTarget,
                int minePow) {
            if (minePow <= 0) {
                return super.build(signer, negotiationTarget);
            }

            this.event.withTag("d", negotiationTarget.getId());

            return signer.getPublicKey().compose(pubkey -> {
                NostrPublicKey counterparty;
                if (negotiationTarget instanceof SdanOfferEvent) {
                    SdanOfferEvent offer = (SdanOfferEvent) negotiationTarget;
                    if (pubkey.equals(offer.getPubkey())) {
                        counterparty = offer.getCounterparty();
                    } else {
                        counterparty = offer.getPubkey();
                    }
                } else if(negotiationTarget instanceof SdanBidEvent){
                    counterparty = ((SdanBidEvent) negotiationTarget).getDelegate();
                } else {
                    throw new IllegalArgumentException("Negotiation target must be an offer or bidding event");
                }

                this.event.withTag("p", counterparty.asHex());

                return signer.encrypt(NGEPlatform.get().toJSON(content), counterparty).compose(encrypted -> {
                    event.withContent(encrypted);
                    return signer.powSign(event, minePow);
                }).then(signed -> {
                    return this.factory.create(signer, signed,
                            negotiationTarget instanceof SdanOfferEvent ? (SdanOfferEvent) negotiationTarget : null,
                            content);
                })
                .then(ev -> {
                    return (T) ev;
                });

            });

        }
    }
}
