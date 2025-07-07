package org.ngengine.nostrads.client.negotiation;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.ngengine.lnurl.LnUrl;
import org.ngengine.lnurl.LnUrlPay;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdAcceptOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdNegotiationEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPaymentRequestEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPayoutEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Handle negotiation from the side of a delegate in the Ad network.
 */
public class DelegateNegotiationHandler extends NegotiationHandler{
    private final static Logger logger=Logger.getLogger(DelegateNegotiationHandler.class.getName());
    private final LnUrl lnurl;

    /**
     * Callback to notify a payout to the offerer
     */
    public static interface NotifyPayout{
        AsyncTask<Void> call(String message, String preimage);
    }

    public static interface AdvListener extends NegotiationHandler.Listener{
        void onPaymentRequest(NegotiationHandler neg, AdPaymentRequestEvent event, String invoice, NotifyPayout notifyPayout);
    }

    public DelegateNegotiationHandler(@Nonnull LnUrl lnurl,@Nonnull NostrPool pool,@Nonnull NostrSigner signer,@Nonnull AdBidEvent bid,int maxDiff){
        super(pool,signer,bid,maxDiff);
        this.lnurl=lnurl;

    }

    /**
     * Handle delegate events
     */
    @Override
    protected void onEvent(AdNegotiationEvent event) {
        if(isClosed()) return;
        super.onEvent(event);
        try{
            // the offerer is requesting us to pay
            if(event instanceof AdPaymentRequestEvent){
                logger.fine("Received payment request event: "+event.getId()+" for bidding: "+getBidEvent().getId());
                AdPaymentRequestEvent paymentRequestEvent=(AdPaymentRequestEvent)event;
                lnurl.getService().then(r -> {
                    try{
                        logger.fine("Got LnUrl service: "+r+", fetching invoice for payment request: "+paymentRequestEvent.getId());
                        LnUrlPay payRequest=(LnUrlPay)r;
                        // fetch an invoice
                        payRequest.fetchInvoice(getBidEvent().getBidMsats(),"Payment for "+this.getBidEvent().getId(),null).then(res -> {
                            String invoice=res.getPr();
                            logger.fine("Fetched invoice for payment request: "+paymentRequestEvent.getId()+": "+invoice);
                            AtomicBoolean done=new AtomicBoolean(false); // we make sure to notify only once even if we have many listeners
                            for(Listener listener:getListeners()){
                                if(listener instanceof AdvListener){
                                    ((AdvListener)listener).onPaymentRequest(this,paymentRequestEvent,invoice,(message, preimage) -> {
                                        if(!done.getAndSet(true)){ 
                                            return notifyPayout(message,preimage,null);
                                         }
                                        return NGEPlatform.get().wrapPromise((res2, rej2) -> {
                                            res2.accept(null);
                                        });
                                    });
                                }
                            }
                            return null;
                        }).catchException(e -> {
                            logger.warning("Failed to fetch invoice for payment request: "+e.getMessage());
                            // we can't get an invoice, so we bail out
                            bail(AdBailEvent.Reason.FAILED_PAYMENT);
                        });
                    }catch(Exception e){
                        throw new RuntimeException("Failed to process LnUrl service: "+e.getMessage(),e);
                    }
                    return null;
                }).catchException(e -> {
                    logger.warning("Failed to get LnUrl service: "+e.getMessage());
                    // we can't get the lnurlp service, so we bail out
                    bail(AdBailEvent.Reason.NO_ROUTE);

                });
            }
        }catch(Exception e){
            logger.warning("Error processing event: "+e.getMessage());
        }
        

    }

    /**
     * Accept an offer from the counterparty and open the negotiation with it.
     * @param offer
     * @param expiration
     * @return an AsyncTask that will complete when the accept event is published
     */
    public AsyncTask<Void> acceptOffer(AdOfferEvent offer, @Nullable Instant expiration) {
        return getSigner().getPublicKey().compose(pubkey -> {
            // ensure the counterparty is valid
            if(pubkey.equals(offer.getPubkey())){ throw new IllegalArgumentException("You cannot accept your own offer"); }
            if(!pubkey.equals(getBidEvent().getDelegate())){ throw new IllegalArgumentException("You can only accept offers for a bidding you are a delegate for"); }

            // open negotiation with offer
            return super.open(offer).compose(sub -> {
                if(isClosed()) return null;
                // create accept event
                AdAcceptOfferEvent.AdAcceptOfferBuilder builder=new AdAcceptOfferEvent.AdAcceptOfferBuilder();
                builder.requestDifficulty(getCounterpartyPenalty());
                if(expiration!=null){
                    builder.withExpiration(expiration);
                }
                return builder.build(getSigner(),offer,getLocalPenalty());
            });

        }).then(sevent -> {
            logger.fine("Sending accept offer event for bid: "+getBidEvent().getId()+": "+sevent);
            // publish the accept event and return 
            getPool().publish(sevent).then(ack -> {
                return sevent;
            });

            return null;
        });
    }

    /**
     * Notify the offerer of a payout.
     * 
     * @param message a message to include in the payout event
     * @param preimage the preimage of the payment request, used to prove the payment
     * @param expiration an optional expiration time for the payout event, if null it will not be set
     * @return an AsyncTask that will complete when the payout event is published
     */
    protected AsyncTask<Void> notifyPayout(String message, String preimage, @Nullable Instant expiration) {
        AdPayoutEvent.PayoutBuilder builder=new AdPayoutEvent.PayoutBuilder();
        if(expiration!=null){
            builder.withExpiration(expiration);
        }
        builder.withMessage(message);
        builder.withPreimage(preimage);

        return builder.build(getSigner(),this.getOffer()).compose(ev -> {
            logger.fine("Sending notify payout event for bid: "+getBidEvent().getId()+": "+ev);

            return getPool().publish(ev).then(ack -> {
                return null;
            });
        });
    }
}