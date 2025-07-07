package org.ngengine.nostrads.client.services.delegate;


import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.lnurl.LnUrl;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler.AdvListener;
import org.ngengine.nostrads.client.negotiation.DelegateNegotiationHandler.NotifyPayout;
import org.ngengine.nostrads.client.services.AbstractAdService;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdNegotiationEvent;
import org.ngengine.nostrads.protocol.negotiation.AdOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdPaymentRequestEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.Wallet;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * DelegateService is responsible for handling bids and negotiations in the Ad network.
 * It listens for bid events, processes them, and manages negotiations with offerers.
 * 
 * This is a specialized service that is usually run as a background task on an always-online server.
 */
public class DelegateService extends AbstractAdService {
    private final static Logger logger = Logger.getLogger(DelegateService.class.getName());
    private final Map<AdBidEvent, NostrSubscription> handlingBids;
    private final Function<DelegateNegotiationHandler, AsyncTask<Boolean>> filterNegotiations;
    private final Function<AdBidEvent, AsyncTask<Boolean>> filterBids;
    private final PenaltyStorage penaltyStorage;

    /**
     * Creates a new DelegateService instance.
     * @param pool the NostrPool to use for network operations
     * @param signer the NostrSigner to use for signing events 
     * @param taxonomy the AdTaxonomy to use for categorizing ads (null to instantiate a default taxonomy)
     * @param filterNegotiations a function that takes a AdDelegateNegotiationHandler and returns an AsyncTask that resolves to true if the negotiation is accepted, false otherwise. Pass null to accept all negotiations by default.
     * @param filterBids a function that takes a AdBidEvent and returns an AsyncTask that resolves to true if the bid is accepted, false otherwise. Pass null to accept all bids by default.
     */
    public DelegateService(
        @Nonnull NostrPool pool, 
        @Nonnull NostrSigner signer, 
        @Nullable AdTaxonomy taxonomy,
        @Nullable Function<DelegateNegotiationHandler, AsyncTask<Boolean>> filterNegotiations,
        @Nullable Function<AdBidEvent, AsyncTask<Boolean>> filterBids,
            @Nonnull PenaltyStorage penaltyStorage
    ) {
        super(pool, signer, taxonomy);
        this.filterNegotiations = filterNegotiations != null ? filterNegotiations : (neg) -> {
            return NGEPlatform.get().wrapPromise((res, rej) -> {
                res.accept(true); // accept all negotiations by default
            });
        };
        this.filterBids = filterBids != null ? filterBids : (bid) -> {
            return NGEPlatform.get().wrapPromise((res, rej) -> {
                res.accept(true); // accept all bids by default
            });
        };
        this.handlingBids=new ConcurrentHashMap<>();
        this.penaltyStorage = penaltyStorage;

        registerCloser(NGEPlatform.get().registerFinalizer(this,() -> {
            for(NostrSubscription sub:handlingBids.values()){
                try{
                    sub.close();
                }catch(Exception e){
                    logger.log(Level.WARNING,"Error closing subscription",e);
                }
            }
         }));
    }

    /**
     * Starts listening for bids on nostr
     * @param since the instant from which to start listening for bids, if null it will listen from 5 minutes since now.
     * @throws Exception
     */
    public void listen(Instant since) throws Exception {
        if(since==null){
            since = Instant.now().minus(Duration.ofMinutes(5));
        }
        
        logger.fine("Listening for bids since: " + since);
        NostrSubscription sub = getPool().subscribe(  
            new NostrFilter() 
            .withKind(AdBidEvent.KIND) 
            .withTag("D", getSigner().getPublicKey().await().asHex())
            .since(since)
        );
        registerCloser(NGEPlatform.get().registerFinalizer(this, () -> {            
            sub.close();
        }));
        sub.addEventListener(this::onNewBid);
        sub.open().catchException(ex->{
            logger.log(Level.WARNING, "Error opening subscription for bids", ex);
            this.close();
        });
    }




    /**
     * Stop handling a specific bid event before it is completed.
     * @param bidEvent the bid event to stop handling
     */
    public void close(AdBidEvent bidEvent) {
        NostrSubscription sub = handlingBids.remove(bidEvent);
        if (sub != null) {
            sub.close();
        } else {
            logger.warning("No subscription found for bid event: " + bidEvent.getId());
        }
    }

    /**
     * Called when a new bid event is discovered
     * @param event the signed nostr event representing the bid
     * @param stored whether the event was stored in the nostr pool (unused)
     */
    protected void onNewBid(SignedNostrEvent event, boolean stored) {
        logger.finest("New bid event received: "+event);

        if(isClosed())return;
        AdBidEvent bid = new AdBidEvent(getTaxonomy(), event);
        if (!bid.isValid())  {
            logger.warning("Invalid bid event received: " + bid.getId());
            return; // Ignore invalid bids
        }
        
        // check if the bid can be handled (user-defined filter)
        this.filterBids.apply(bid).then(accepted -> {
            if (accepted) { // the bid was accepted for handliong
                logger.info("New bid received: " + bid.getId());
                handleBid(bid).catchException(ex -> {
                    logger.log(Level.WARNING, "Error handling bid: " + bid.getId(), ex);
                });
            } else {
                logger.info("Bid rejected by filter: " + bid.getId());
            }
            return null;
        }).catchException(ex -> {
            logger.log(Level.WARNING, "Error applying filter to bid: " + bid.getId(), ex);
        });

    }
 

    /**
     * A listener that manages the negotiation lifecycle, one instance of the listener is shared by all the negotiations
     * of a specific bid.
     */
    private class Listener implements AdvListener {

        private final long budgetMsats;
        private final Wallet wallet;
        private long spent = 0;

        /**
         * A pending offer that is holding some budget for a negotiation.
         */
        private static record PendingOffer(
                AdOfferEvent offer,
                NegotiationHandler neg,
                Instant expiration) {
        }

        // pending offers are recorded here and are used to track the budget that is being held for active negotiations
        private final ArrayList<PendingOffer> pendingOffers = new ArrayList<>();

        /**
         * Creates a new listener for handling negotiations.
         * @param wallet the wallet to use for payments
         * @param budgetMsats the maximum budget in millisatoshis for this listener 
         */
        Listener(  Wallet wallet,  long budgetMsats) {
            this.budgetMsats = budgetMsats;
            this.wallet = wallet;
        }


        /**
         * Clean expired offers and release their held budget.
         */
        synchronized void cleanExpired() {
            Iterator<PendingOffer> it = pendingOffers.iterator();
            while (it.hasNext()) {
                PendingOffer p = it.next();
                Instant now = Instant.now();
                if ((p.offer().getExpiration() != null && p.offer().getExpiration().isBefore(now)) ||
                        (p.expiration != null && p.expiration.isBefore(now))) {
                    logger.fine("Removing expired offer: " + p);
                    it.remove();
                    p.neg.close();
                }
            }
        }

        /**
         * Check if there is enough budget left to accept the offer for the given negotiation.
         * @param neg the negotiation handler for which to check the budget
         * @return true if there is enough budget left, false otherwise
         */
        synchronized boolean checkBudget(NegotiationHandler neg) {
            cleanExpired();
            AdBidEvent bid = neg.getBidEvent();
            long budgetLeft = budgetMsats - spent - pendingOffers.size() * bid.getBidMsats();
            if (budgetLeft < bid.getBidMsats()) {
                logger.warning("Not enough budget left to accept offer for " + neg+" "+bid.getBidMsats()+
                        " msats, budget left: " + budgetLeft + " msats ("+spent+" spent, "+pendingOffers.size()+" pending offers)");
                
                return false;
            }
            return true;
        }

        /**
         * Add a pending offer to the listener, this will hold the budget for the negotiation.
         * @param neg the negotiation handler for which to add the pending offer
         * @param offer the offer event to add as pending
         * @return true if the offer was added, false if there is not enough budget left
         */
        synchronized boolean addPendingOffer(NegotiationHandler neg, AdOfferEvent offer) {
            if (!checkBudget(neg)){
                neg.bail(AdBailEvent.Reason.OUT_OF_BUDGET, offer);
                return false;
            }
            pendingOffers.add( new PendingOffer(offer, neg, Instant.now().plus(neg.getBidEvent().getHoldTime())));
            return true;
        }

 

        /**
         * Detect when a negotiation handler is closed due to a bail event.
         * This will remove the pending offer from the list of pending offers.
         * @param neg the negotiation handler that was bailed
         * @param event the bail event that was triggered
         */
        @Override
        public synchronized void onBail(NegotiationHandler neg, AdBailEvent event) {
            logger.fine("Bail event received: " + event);
            // find and remove the bailed offer, this will release the budget too.
            Iterator<PendingOffer> it = pendingOffers.iterator();
            while (it.hasNext()) {
                PendingOffer p = it.next();
                if (p.neg.equals(neg)) {
                    it.remove();
                    logger.fine("Removing bailed offer: " + p);
                    break;
                }
            }
            // penaltyStorage.set(neg);

        }

        /**
         * Called when a payment request event is received.
         * This will process the payment request if there is enough budget left and if the offer relative to the negotiation
         * is still pending
         */
        @Override
        public synchronized void onPaymentRequest(NegotiationHandler neg, AdPaymentRequestEvent event, String invoice, NotifyPayout notifyPayout) {
            try {
                logger.fine("Payment request event received: " + event);

           
                // find the pending offer for this negotiation
                PendingOffer pendingOffer = null;
                
                Iterator<PendingOffer> it = pendingOffers.iterator();
                while (it.hasNext()) {
                    pendingOffer = it.next();
                    if (pendingOffer.neg.equals(neg)) {
                        logger.fine("Found pending offer for negotiation: " + pendingOffer);
                        break;
                    }
                }

                // if no pending offer was found, it means the negotiation is not valid anymore, bail
                if(pendingOffer ==null) {
                    logger.warning("No pending offer found for negotiation: " + neg+" maybe it expired?");
                    neg.bail(AdBailEvent.Reason.EXPIRED);
                    return;
                }

       
                logger.finer("Processing payment request for negotiation: " + neg + ", invoice: " + invoice);

                // remove the pending offer from the list of pending offers
                it.remove();

                // register spent amount
                spent += neg.getBidEvent().getBidMsats();
                                

                // pay the invoice
                PayResponse res = wallet.payInvoice(invoice, neg.getBidEvent().getBidMsats()).await();

                logger.finer("Payment request processed successfully: " + res+" calling notifyPayout");
                // notify the counterparty             
                notifyPayout.call(
                        "NOSTR-Ads: Payout for " + neg.getBidEvent().getAdId() + " completed!",
                        res.preimage()).then(v->{
                            // mark negotiation as completed
                            // complete negotiations will be closed asap
                            neg.markCompleted();
                            // penaltyStorage.set(neg);
                            return null;
                        }).catchException(ex->{
                            logger.log(Level.WARNING, "Error notifying payout: " + ex.getMessage(), ex);
                            // if notifyPayout fails, we bail the negotiation
                            neg.bail(AdBailEvent.Reason.FAILED_PAYMENT);
                        });
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to process payment request: " + e.getMessage(), e);
                neg.bail(AdBailEvent.Reason.NO_ROUTE);
            }
        }

   
    }


    /**
     * Handle a bid event.
     * This is done automatically if {@link #listen(Instant)} is used, use this method instead of relying on the
     * automatic handling if you want to pass the bids outside of the nostr protocol (eg. with a rest api).
     * 
     * To stop handling a bid, you can call {@link #close(AdBidEvent)}, or {@link #close()} if you wish to stop all bids and kill the service.
     * 
     * Note: this service is compatible only with bids that include a NWC payload in their delegate payload.
     * 
     * @param bidEvent the bid event to handle
     * @return an AsyncTask that resolves when the bid is being handled.
     */
    public AsyncTask<Void> handleBid(AdBidEvent bidEvent) {
        if (handlingBids.containsKey(bidEvent)) {
            throw new IllegalStateException("Bid already being handled: " + bidEvent.getId());
        }
        logger.fine("Handling bid: "+bidEvent.getId());

        // get the whitelist values
        List<NostrPublicKey> bidTargets = bidEvent.getTargetedOfferers();
        List<NostrPublicKey> appTargets = bidEvent.getTargetedApps();

        // subscribe to the negotiations related to this bid
        NostrSubscription sub = getPool().subscribe(
            new NostrFilter()
                    .withTag("p", bidEvent.getDelegate().asHex())
                    .withKind(AdNegotiationEvent.KIND)
                    .limit(1)
                    .withTag("d", bidEvent.getId()));
        
   
        return bidEvent.getDecryptedDelegatePayload(getSigner()).then(payload->{
            try{               
                // grab nwc payload and initialize the wallet
                String nwc = NGEUtils.safeString(Objects.requireNonNull( payload.get("nwc")));
                long budget = NGEUtils.safeLong(Objects.requireNonNull(payload.get("budget")));
                NWCWallet wallet = new NWCWallet(new NWCUri(nwc));

                // prepare a listener that will be shared by all the negotiations with a shared budget
                Listener listener = new Listener(wallet, budget);

                sub.addEventListener((event, stored) -> {
                    logger.finest("New negotiation event received: " + event.getId());
                    // cast the event, we are looking only for Ad offers.
                    AdNegotiationEvent.cast(getSigner(), event, null).then(ev -> {
                        if (!(ev instanceof AdOfferEvent)) return null; // this is not the droid you are looking for
                        if( isClosed()) return null; // service is closed, no need to handle this offer
                        logger.finest("Processing offer event: " + ev.getId());
                        AdOfferEvent offer = (AdOfferEvent) ev;

                        // ignore events from an offerer that was not targeted by the bid
                        if (bidTargets != null && !bidTargets.contains(offer.getPubkey())){
                            logger.finest("Ignoring offer from non-targeted offerer: " + offer.getPubkey().asHex());
                            return null;
                        }

                        // ignore events from an offerer that belongs to an app that was not targeted by the bid
                        if (appTargets != null && !appTargets.contains(offer.getAppPubkey())){
                            logger.finest("Ignoring offer from non-targeted app: " + offer.getAppPubkey().asHex());
                            return null;
                        }

                        // we are going to need also the lnurl payment address.
                        // otherwise there is no point in handling the offer, at all
                        Nip01.fetch(getPool(), offer.getAppPubkey()).then(nip01 -> {
                            try {
                                if(isClosed()) return null; // service is closed, no need to handle this offer
                                logger.finest("Nip01 fetched for offer: " + offer.getId() +":"+ nip01);
                                LnUrl lnurl = nip01.getPaymentAddress();
                        
                                // lets prepare the negotiation
                                DelegateNegotiationHandler neg = new DelegateNegotiationHandler( lnurl, getPool(), getSigner(), bidEvent, getMaxDiff());

                                // we are going to filter it first with the user-defined filter, if it doesn't pass -> we don't want it
                                this.filterNegotiations.apply(neg).compose(accepted -> {
                                 
                                    return penaltyStorage.get(neg.getBidEvent()).then(penalty -> {
                                        if(isClosed()) return null; // service is closed, no need to handle this offer
                                        logger.fine("Negotiation filter result for offer "+offer.getId()+": "+accepted);
                                        if(accepted){
                                            // we register the negotiation, the parent class will track and close it when needed (eg. if it expires)
                                            registerNegotiation(neg);
                                            // we add the listener that handles the lifecycle
                                            neg.addListener(listener);

                                            // set the counterparty penalty
                                            neg.setCounterpartyPenalty(penalty);
                                            if(penalty>0){
                                                logger.fine("Negotiation has a penalty: "+penalty+" msats");
                                            } else{
                                                logger.fine("Negotiation has no penalty");
                                            }

                                            // we register this as pending offer in the listener (used to reserve budget)
                                            if(!listener.addPendingOffer(neg,offer)){ // out of budget, nothing to do
                                                logger.warning("Not enough budget left to accept offer: "+offer.getId());
                                                neg.bail(AdBailEvent.Reason.OUT_OF_BUDGET,offer);
                                            }else{ // open the negotiation and start handling it
                                                logger.fine("Accepting offer: "+offer.getId());
                                                neg.acceptOffer(offer,null);
                                            }
                                        }else{
                                            logger.fine("Negotiation rejected by filter: "+offer.getId());
                                        }
                                       
                                        return null;
                                    });
                                }).catchException(ex -> {
                                    logger.log(Level.WARNING, "Error filtering negotiation: " + bidEvent.getId(), ex);
                                    neg.close(); // neg might be partially initialized, so we close it to avoid leaks
                                });                       

                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error processing event: " + event.getId(), e);
                            }
                            return null;
                        }).catchException(ex -> { // no lnurl -> no handling
                            logger.log(Level.WARNING, "Error fetching nip01 for event: " + event.getId(), ex);
                        });
                        return null;                        
                    }).catchException(ex -> {
                        logger.log(Level.WARNING, "Error processing event: " + event.getId(), ex);
                    });
                });

                // when the sub closes, we ensure to remove the bid from the handlingBids map
                sub.addCloseListener(reason -> {
                    logger.fine("Subscription closed for bid: " + bidEvent.getId() + ", reason: " + reason);
                    handlingBids.remove(bidEvent);
                });
             
                // we need to track this bid sub so we can close it later if needed
                handlingBids.put(bidEvent, sub);

                // start subscription
                sub.open();

                return null;
            } catch (Exception e) {
              throw new RuntimeException("Failed to handle bid: " + bidEvent.getId(), e);
            }
        });
    }

}
