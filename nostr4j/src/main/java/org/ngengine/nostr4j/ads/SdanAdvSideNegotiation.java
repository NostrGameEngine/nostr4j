package org.ngengine.nostr4j.ads;

import java.time.Instant;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.ads.negotiation.SdanAcceptOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanNegotiationEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPaymentRequestEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPayoutEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

import jakarta.annotation.Nullable;

public class SdanAdvSideNegotiation extends SdanNegotiation {
    private final static Logger logger = Logger.getLogger(SdanAdvSideNegotiation.class.getName());
    public static interface AdvListener extends SdanNegotiation.Listener {
        boolean onOffer(SdanNegotiation neg, SdanOfferEvent offer, Runnable acceptOffer);
        boolean onPaymentRequest(SdanPaymentRequestEvent event, BiConsumer<String, String> notifyPayout);
    }
    
    protected SdanAdvSideNegotiation(
        Function<NostrPublicKey, Number> initialPenaltyProvider,
        NostrPublicKey appKey,
        NostrPool pool,
        NostrSigner signer,
        SdanBidEvent bidding,
        int maxDiff,
        int penaltyIncrease
    ) {
        super(initialPenaltyProvider, appKey, pool, signer, bidding, maxDiff, penaltyIncrease);
        
     
    }

    protected void init(SdanOfferEvent offerEvent){
        for (Listener listener : listeners) {
            if (listener instanceof AdvListener) {
                ((AdvListener) listener).onOffer(this, offerEvent, () -> {
                    acceptOffer(offerEvent, null);
                });
            }
        }
    }

    @Override
    protected void onEvent(SdanNegotiationEvent event) {
        super.onEvent(event);
   
        if (event instanceof SdanPaymentRequestEvent) {
            SdanPaymentRequestEvent paymentRequestEvent = (SdanPaymentRequestEvent) event;
            for (Listener listener : listeners) {
                if (listener instanceof AdvListener) {
                    ((AdvListener) listener).onPaymentRequest(paymentRequestEvent, (message, preimage) -> {
                        notifyPayout(message, preimage, null);
                    });                       
                }
            }
        }
 
    }


    // from advertiser
    protected AsyncTask<SdanAcceptOfferEvent> acceptOffer(
            SdanOfferEvent offer,
            @Nullable Instant expiration) {
        return signer.getPublicKey().compose(pubkey -> {
            if (pubkey.equals(offer.getPubkey())) {
                throw new IllegalArgumentException("You cannot accept your own offer");
            }
            if(!pubkey.equals(bidding.getDelegate())) {
                throw new IllegalArgumentException("You can only accept offers for a bidding you are a delegate for");
            }
   
                // calculate initial penalty
                int initDiff = initialPenaltyProvider.apply(offer.getPubkey()).intValue();
                this.penaltyAppliedToTheCounterparty = initDiff;

                // apply offer penalty
                this.penaltyAppliedToUs = offer.getRequestedDifficultyToRespond();

                // init with offer
                super.init(offer);

                // create accept event
                SdanAcceptOfferEvent.Builder builder = new SdanAcceptOfferEvent.Builder();
                builder.requestDifficulty(penaltyAppliedToTheCounterparty);
                if (expiration != null) {
                    builder.withExpiration(expiration);
                }
                return builder.build(signer, offer, penaltyAppliedToUs);
        }).then(sevent -> {
            // publish the accept event and return it
            pool.publish(sevent).then(ack -> {
                return sevent;
            });

            return sevent;
        });
    }

    // from offerer
    protected AsyncTask<SdanPayoutEvent> notifyPayout(
            String message,
            String preimage,
            @Nullable Instant expiration) {
        SdanPayoutEvent.Builder builder = new SdanPayoutEvent.Builder();

        if (expiration != null) {
            builder.withExpiration(expiration);
        }
        builder.withMessage(message);
        builder.withPreimage(preimage);

        return builder.build(signer, this.offer).compose(ev->{
            return pool.publish(ev).then(ack -> {
                return ev;
            });
        });
    }
}