package org.ngengine.nostr4j.ads;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.ads.SdanNegotiation.Listener;
import org.ngengine.nostr4j.ads.negotiation.SdanAcceptOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanNegotiationEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPaymentRequestEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPayoutEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;

import jakarta.annotation.Nullable;

public class SdanOffererSideNegotiation extends SdanNegotiation {
    private final static  Logger logger = Logger.getLogger(SdanOffererSideNegotiation.class.getName());

    public static interface OfferListener extends SdanNegotiation.Listener {
        void verifyPayout(SdanNegotiation neg, SdanPayoutEvent event);

        void onRequestingPayment(SdanNegotiation neg );

        void showAd(SdanNegotiation neg, SdanAcceptOfferEvent acp, Consumer<String> notifyShown);
        void onBid(SdanOffererSideNegotiation neg,   Runnable makeOffer);
    }

    protected SdanOffererSideNegotiation(
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

    protected void init(){
        logger.fine("Initializing offerer side negotiation for bidding: " + bidding.getId());
        for (Listener listener : listeners) {
            if (listener instanceof OfferListener) {
                ((OfferListener) listener).onBid(this,  () -> {
                    makeOffer(null);
                });
            }
        }

    }

    @Override
    protected void onEvent(SdanNegotiationEvent event) {
         super.onEvent(event);//.then(event->{
            if (event instanceof SdanAcceptOfferEvent) {
                SdanAcceptOfferEvent acceptEvent = (SdanAcceptOfferEvent) event;
                logger.fine("Received accept offer event: " + acceptEvent.getId() + " for bidding: " + bidding.getId());
                for (Listener listener : listeners) {
                    if (listener instanceof OfferListener) {
                        ((OfferListener) listener).showAd(this, acceptEvent, (msg)->{
                            logger.fine("Ad was shown "+ msg + " for bidding: " + bidding.getId()+" ... requesting payment");
                            for (Listener l : listeners) {
                                if (l instanceof OfferListener) {
                                    ((OfferListener) l).onRequestingPayment(this);
                                }
                            }
                            requestPayment(msg,null);
                        });
                    }
                }
            } else if (event instanceof SdanPayoutEvent) {
                SdanPayoutEvent payoutEvent = (SdanPayoutEvent) event;
                logger.fine("Received payout event: " + payoutEvent.getId() + " for bidding: " + bidding.getId());
                for (Listener listener : listeners) {
                    if (listener instanceof OfferListener) {
                        ((OfferListener) listener).verifyPayout(this,  payoutEvent);
                    }
                }
            }
 
    }

    protected AsyncTask<SdanOfferEvent> makeOffer(@Nullable Instant expiration) {
        logger.fine("Making offer for bidding: " + bidding.getId() + " with expiration: " + expiration);
        return signer.getPublicKey().compose(pubkey -> {
            if (pubkey.equals(bidding.getPubkey())) {
                throw new IllegalArgumentException("You cannot offer to yourself");
            }
            // calculate initial penalty
            int initDiff = initialPenaltyProvider.apply(bidding.getPubkey()).intValue();
            this.penaltyAppliedToTheCounterparty = initDiff;

            // create the offer event
            SdanOfferEvent.Builder builder = new SdanOfferEvent.Builder(appKey);
            builder.requestDifficulty(initDiff);
            builder.withExpiration(expiration);

            return builder.build(signer, bidding);
        }).then(sevent -> {
            logger.fine("Making offer for bidding: " + bidding.getId() + ": "+sevent);

            // initialize with this offer
            init(sevent);

            // publish the offer event and return it
            pool.publish(sevent).then(ack -> {
                return sevent;
            });
            return sevent;
        });
    }

    protected AsyncTask<SdanPaymentRequestEvent> requestPayment(String message,  @Nullable Instant expiration) {
        logger.fine("Requesting payment for bidding: " + bidding.getId() + " with message: " + message + " and expiration: " + expiration);
        SdanPaymentRequestEvent.Builder builder = new SdanPaymentRequestEvent.Builder();

        if (expiration != null) {
            builder.withExpiration(expiration);
        }
        builder.withMessage(message);

        return builder.build(signer, this.offer).compose(sevent->{
            return pool.publish(sevent).then(ack -> {
                return sevent;
            });
        });
    }

}