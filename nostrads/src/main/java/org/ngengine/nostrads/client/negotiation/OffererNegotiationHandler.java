package org.ngengine.nostrads.client.negotiation;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdAcceptOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdNegotiationEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPaymentRequestEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPayoutEvent;
import org.ngengine.platform.AsyncTask;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Handle negotiation from the side of an offerer in the Ad network.
 */
public class OffererNegotiationHandler extends NegotiationHandler{
    private final static Logger logger=Logger.getLogger(OffererNegotiationHandler.class.getName());
    private final NostrPublicKey appKey;

    public static interface OfferListener extends NegotiationHandler.Listener{
        void verifyPayout(NegotiationHandler neg, AdPayoutEvent event);
        void onRequestingPayment(NegotiationHandler neg);
        void showAd(NegotiationHandler neg, AdAcceptOfferEvent acp, Consumer<String> notifyShown);
    }

    public OffererNegotiationHandler(@Nonnull NostrPublicKey appKey,@Nonnull NostrPool pool,@Nonnull NostrSigner signer,@Nonnull AdBidEvent bidding,int maxDiff){
        super(pool,signer,bidding,maxDiff);
        this.appKey=appKey;
    }

    @Override
    protected void onEvent(AdNegotiationEvent event) {
        if(isClosed()) return;
        super.onEvent(event);
        if(event instanceof AdAcceptOfferEvent){
            // show ad and request payment
            AdAcceptOfferEvent acceptEvent=(AdAcceptOfferEvent)event;
            logger.fine("Received accept offer event: "+acceptEvent.getId()+" for bidding: "+getBidEvent().getId());
            AtomicBoolean done=new AtomicBoolean(false);
            for(Listener listener:getListeners()){
                if(listener instanceof OfferListener){
                    ((OfferListener)listener).showAd(this,acceptEvent,(msg) -> {
                        if(!done.getAndSet(true)){
                            logger.fine("Ad was shown "+msg+" for bidding: "+getBidEvent().getId()+" ... requesting payment");
                            for(Listener l:getListeners()){
                                if(l instanceof OfferListener){
                                    ((OfferListener)l).onRequestingPayment(this);
                                }
                            }
                            requestPayment(msg,null);
                        }
                    });
                }
            }
        }else if(event instanceof AdPayoutEvent){
            //  notify listeners
            AdPayoutEvent payoutEvent=(AdPayoutEvent)event;
            logger.fine("Received payout event: "+payoutEvent.getId()+" for bidding: "+getBidEvent().getId());
            for(Listener listener:getListeners()){
                if(listener instanceof OfferListener){
                    ((OfferListener)listener).verifyPayout(this,payoutEvent);
                }
            }
        }

    }

    /**
     * Make an offer for the bid associated with this negotiation handler.
     * @param expiration  the expiration time for the offer, or null for no expiration
     * @return an AsyncTask that resolves to the AdOfferEvent created for the offer
     */
    public AsyncTask<Void> makeOffer(@Nullable Instant expiration) {
        logger.fine("Making offer for bidding: "+getBidEvent().getId()+" with expiration: "+expiration);
        return getSigner().getPublicKey().compose(pubkey -> {
            if(pubkey.equals(getBidEvent().getPubkey())){ throw new IllegalArgumentException("You cannot offer to yourself"); }
            AdOfferEvent.OfferBuilder builder=new AdOfferEvent.OfferBuilder(appKey);
            // request a pow difficulty from the counterparty if applicable
            builder.requestDifficulty(getCounterpartyPenalty());
            builder.withExpiration(expiration);
            return builder.build(getSigner(),getBidEvent());
        }).then(sevent -> {
            logger.fine("Sending offer event for bid: "+getBidEvent().getId()+": "+sevent);

            // initialize with this offer
            open(sevent).then(sub -> {
                if(isClosed()) return null;
                // publish the offer event and return it
                getPool().publish(sevent).then(ack -> {
                    return sevent;
                });
                return null;
            });
            return null;
        });
    }

    /**
     * Request a payment for the bidding associated with this negotiation handler.
     * @param message a message to include in the payment request event
     * @param expiration an optional expiration time for the payment request, if null it will not be set
     * @return an AsyncTask that will complete when the payment request event is published
     */
    protected AsyncTask<Void> requestPayment(String message, @Nullable Instant expiration) {
        logger.fine("Requesting payment for bidding: "+getBidEvent().getId()+" with message: "+message+" and expiration: "+expiration);
        AdPaymentRequestEvent.PaymentRequestBuilder builder=new AdPaymentRequestEvent.PaymentRequestBuilder();

        if(expiration!=null){
            builder.withExpiration(expiration);
        }
        builder.withMessage(message);

        return builder.build(getSigner(),getOffer(),getLocalPenalty()).compose(sevent -> {
            logger.fine("Sending payment request event for bid: "+getBidEvent().getId()+": "+sevent);
            return getPool().publish(sevent).then(ack -> {
                return null;
            });
        });
    }

}