package org.ngengine.nostrads.client.services.display;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrPoolFetchPolicy;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrWaitForEventFetchPolicy;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.client.negotiation.OffererNegotiationHandler;
import org.ngengine.nostrads.client.negotiation.OffererNegotiationHandler.OfferListener;
import org.ngengine.nostrads.client.services.AbstractAdService;
import org.ngengine.nostrads.client.services.PenaltyStorage;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.AdBidFilter;
import org.ngengine.nostrads.protocol.negotiation.AdAcceptOfferEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent.Reason;
import org.ngengine.nostrads.protocol.negotiation.AdPayoutEvent;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Client for displaying ads from the Ad network.
 */
public class AdsDisplayClient extends AbstractAdService{
    private final static Logger logger=Logger.getLogger(AdsDisplayClient.class.getName());

    private final Map<Adspace,BidsQueue> adspaces=new HashMap<>();
    private final NostrPublicKey appKey;
    private final PenaltyStorage penaltyStorage;
    private int penaltyIncrease=1; // default penalty increase for failed negotiations

    /**
     * Constructor for AdsDisplayClient.
     * @param pool the NostrPool to use for network operations
     * @param signer the NostrSigner to use for signing events
     * @param appKey the public key of the app that is displaying ads
     * @param taxonomy the AdTaxonomy to use for categorizing ads (null to instantiate a default taxonomy)
     * @param penaltyStorage the PenaltyStorage to use for storing and retrieving POW penalties
     */
    public AdsDisplayClient(@Nonnull NostrPool pool,@Nonnull NostrSigner signer,@Nonnull NostrPublicKey appKey,@Nullable AdTaxonomy taxonomy,@Nonnull PenaltyStorage penaltyStorage){
        super(pool,signer,taxonomy);
        this.appKey=appKey;
        this.penaltyStorage=penaltyStorage;
    }

    /**
     * Set the value to sum to the penalty for each negotiation that ends with a punishment.
     * @param penaltyIncrease
     */
    public void setPenaltyIncrease(int penaltyIncrease) {
        this.penaltyIncrease=penaltyIncrease;
    }

    /**
     * Register an adspace for displaying ads.
     * If two adspaces are equals, they will share the same queue of bids.
     * @param adspace the adspace to register
     */
    public BidsQueue registerAdspace(Adspace adspace) {
        return adspaces.compute(adspace,(k, v) -> {
            if(v==null){
                return new BidsQueue();
            }else{
                v.refs.incrementAndGet();
                return v;
            }
        });
    }

    /**
     * Unregister an adspace.
     * @param adspace the adspace to unregister
     */
    public void unregisterAdspace(Adspace adspace) {
        adspaces.computeIfPresent(adspace,(k, v) -> {
            if(v.refs.decrementAndGet()<=0){
                return null; // Remove the adspace if no more references
            }else{
                return v;
            }
        });
    }

    /**
     * Listener for handling ad negotiation lifecycle.
     */
    private class Listener implements OfferListener{
        private volatile boolean requestedPayment=false;
        private final Function<AdBidEvent,AsyncTask<Boolean>> showAd;
        private final Runnable onComplete;

        Listener(Function<AdBidEvent,AsyncTask<Boolean>> showAd, @Nullable Runnable onComplete) {
            this.showAd=showAd;
            this.onComplete=onComplete;
        }

 

        @Override
        public void verifyPayout(NegotiationHandler neg, AdPayoutEvent event) {
            logger.finer("Received payout event: "+event.getId()+" for bidding: "+neg.getBidEvent().getId());
            // we do not verify the payouts for now,
            
            // mark negotiation as completed
            neg.markCompleted();

            // remember the penalty
            penaltyStorage.set(neg);

            if(onComplete!=null){
                onComplete.run();
            }
        }

        @Override
        public void onRequestingPayment(NegotiationHandler neg) {
            // this flag will be used to know when to punish the counterparty
            requestedPayment=true;
        }

        @Override
        public void onBail(NegotiationHandler neg, AdBailEvent event) {
            // if bailed after requesting payment, we punish automatically
            if(requestedPayment){
                logger.finer("Bail after payment request, punishing counterparty directly");
                try{
                    neg.punishCounterparty(penaltyIncrease);
                }catch(Exception e){
                    logger.log(Level.WARNING,"Failed to punish counterparty",e);
                }
            }
            penaltyStorage.set(neg);
        }

        @Override
        public void showAd(NegotiationHandler neg, AdAcceptOfferEvent acp, Consumer<String> notifyShown) {
            this.showAd.apply(neg.getBidEvent()).catchException(ex -> {
                // any exception will automatically bail the negotiation
                neg.bail(Reason.AD_NOT_DISPLAYED);
            }).then((result) -> {
                // if the advertiser has penalized us, we counter-penalize them (assuming we are
                // always honest)  this will naturally deprioritize their ads in the future
                if(neg.getLocalPenalty()>0){
                    int p=neg.getCounterpartyPenalty();
                    if(neg.getLocalPenalty()>p){
                        neg.setCounterpartyPenalty(p);
                    }
                }
                notifyShown.accept("Ad shown successfully");
                return null;
            });
        }
    }

    /**
     * Load the best next ad for the given adspace.
     * @param adspace the adspace to load the next ad for
     */
    public void loadNextAd(Adspace adspace) {
        
        if(isClosed()) throw new IllegalStateException("AdClient is closing");
        executor.run(() -> {

            // get or initialize the adspace queue
            BidsQueue queue=adspaces.containsKey(adspace)?adspaces.get(adspace):registerAdspace(adspace);

            // we'll load up to this many bids
            int numBidsToLoad=adspace.getNumBidsToLoad();

            Instant now=Instant.now();

            // Clean up expired bids
            queue.rankedBids.removeIf(bid -> bid.bid.getExpiration()!=null&&bid.bid.getExpiration().isBefore(now));

            AdBidFilter filter=new AdBidFilter();
            filter.limit(numBidsToLoad);
            filter.withPriceSlot(adspace.getPriceSlot());
            if(adspace.getAdvertisersWhitelist()!=null){
                for(NostrPublicKey advertiser:adspace.getAdvertisersWhitelist()){
                    filter.withAuthor(advertiser);
                }
            }
            
            if(adspace.getLanguages()!=null&&adspace.getLanguages().size()>0){
                for(String lang:adspace.getLanguages()){
                    filter.withLanguage(lang);
                }
            }
            // if(adspace.getMimeTypes()!=null&&adspace.getMimeTypes().size()>0){
                for(AdMimeType mime:adspace.getMimeTypes()){
                    filter.withMimeType(mime);
                }
            // }

            ArrayList<RankedBid> mergedBids=new ArrayList<>();

            Instant newestBidTime=queue.newestBidTime;
            Instant oldestBidTime=queue.oldestBidTime;
            if(newestBidTime==null){
                newestBidTime=Instant.now();
            }
            if(oldestBidTime==null){
                oldestBidTime=Instant.now();
            }


            int rounds=0;
            int goodRanks=0;
            // Load bids in rounds, first newer+older, then only older, until we have enough good ranks or we 
            // reach the maximum number of rounds
            while(rounds<3&&goodRanks<numBidsToLoad*2){
                logger.finer("Loading bids for adspace: "+adspace+" (round "+rounds+") using filter: "+filter);
                logger.finer("Newest bid time: "+newestBidTime);
                logger.finer("Oldest bid time: "+oldestBidTime);
                logger.finer("Currently newly loaded bids: "+queue.rankedBids.size());

                AsyncTask<List<AdBidEvent>> newOlderBids=fetchBids(List.of(filter.clone().until(oldestBidTime.plusMillis(2100))));

                List<List<AdBidEvent>> newBids;
                if(rounds==0){
                    AsyncTask<List<AdBidEvent>> newNewerBids=fetchBids(List.of(filter.clone().since(newestBidTime.minusMillis(2100))));
                    newBids=NGEPlatform.get().awaitAll(List.of(newNewerBids,newOlderBids)).await();
                    logger.finer("Loaded "+newBids.get(0).size()+" newer bids and "+newBids.get(1).size()+" older bids");
                }else{ // rounds > 0 only load from the past (it is unlikely that new bids are added in
                       // this small time frame)
                    newBids=NGEPlatform.get().awaitAll(List.of(newOlderBids)).await();
                    logger.finer("Loaded "+newBids.get(0).size()+" older bids");
                }

                // merge and find olderst and newest bid times
                for(int i=0;i<2;i++){
                    if(newBids.size()<=i)continue;
                    for(AdBidEvent bid:newBids.get(i)){
                        try{
                            RankedBid r=new RankedBid(bid,adspace);
                            if(r.getScore()>0) {
                                logger.finest("Adding bid: "+bid.getId()+" with score: "+r.getScore());
                                goodRanks++;
                            }
                            if(newestBidTime==null||bid.getCreatedAt().isAfter(newestBidTime)){
                                newestBidTime=bid.getCreatedAt();
                                logger.finest("New newest bid time: "+newestBidTime);
                            }
                            if(oldestBidTime==null||bid.getCreatedAt().isBefore(oldestBidTime)){
                                oldestBidTime=bid.getCreatedAt();
                                logger.finest("New oldest bid time: "+oldestBidTime);
                            }
                            if(!mergedBids.stream().anyMatch(ro -> ro.bid.getAdId().equals(bid.getAdId()))){
                                mergedBids.add(r);
                                logger.finest("Added bid: "+bid.getId()+" to merged bids, total: "+mergedBids.size());
                            }
                        }catch(Exception e){
                            logger.log(Level.WARNING,"Error processing bid: "+bid.getId(),e);
                        }
                    }
                }

                // increase round counter
                rounds++;
            }

            // we add also the current bids to the merged list so that
            // they contribute to the new ranking
            for(RankedBid rankedBid:queue.rankedBids){
                mergedBids.add(rankedBid);
            }

            logger.finer("Total bids loaded: "+mergedBids.size());

            // load penalties for all bids
            for(RankedBid rb:mergedBids){
                AdBidEvent bid=rb.bid;
                Number n=penaltyStorage.get(bid).await();
                rb.setPenalty(n.intValue());
            }

            logger.finer("Sort bids by score, total loaded: "+mergedBids.size());

            // Sort bids by score highest first
            mergedBids.sort((a, b) -> Double.compare(b.getScore(),a.getScore()));

            logger.finer("Select "+numBidsToLoad+" top bids from "+mergedBids.size()+" loaded bids");
            // Limit to numBidsPerQueue
            if(mergedBids.size()>numBidsToLoad){
                mergedBids=new ArrayList<>(mergedBids.subList(0,numBidsToLoad));
            }

            logger.finer("Selected bids: "+mergedBids.size()+", updating queue");
            // update the queue with the new bids
            synchronized(queue){
                queue.rankedBids.clear();
                queue.rankedBids.addAll(mergedBids);
                queue.newestBidTime=newestBidTime;
                queue.oldestBidTime=oldestBidTime;
            }

            logger.finer("Queue updated for adspace: "+adspace+", total bids: "+queue.rankedBids.size());

            logger.finer("Accepting best bid for "+adspace);
            // Accept the best bid and start negotiation
            for(RankedBid rb:mergedBids){
                // check if the bid is acceptable
                boolean accepted=adspace.getBidFilter().apply(rb.bid).await();
                // if the bid is accepted, we start the negotiation, mark the impression (will be used to derank the bid in the next rounds)
                // and we break the loop
                if(accepted){
                    rb.markImpression();
                    openNegotiation(rb.bid,new Listener(adspace.getShowBidAction(),adspace.getOnCompleteCallback())).then(neg -> {
                        OffererNegotiationHandler oneg=(OffererNegotiationHandler)neg;
                        oneg.makeOffer(null);
                        return null;
                    });
                    break;
                }
            }
            return mergedBids;
        }).catchException(e->{
            logger.log(Level.WARNING,"Error loading next ad for adspace: "+adspace,e);
           
        });

    }

    /** 
     * Fetch all bids given a list of filters.
     * @param filters the list of filters to use for fetching bids
     * @return an AsyncTask that will complete with a list of AdBidEvent
     * @throws IllegalStateException if the AdClient is closing
     */
    public  AsyncTask<List<AdBidEvent>> fetchBids(List<NostrFilter> filters) {
        return fetchBids(filters,null);
    }

    /**
     * Fetch all bids given a list of filters and a fetch policy.
     * @param filters the list of filters to use for fetching bids
     * @param fetchPolicy the fetch policy to use for fetching bids, can be null to use the default
     * @return an AsyncTask that will complete with a list of AdBidEvent
     * @throws IllegalStateException if the AdClient is closing
     * 
     */
    public  AsyncTask<List<AdBidEvent>> fetchBids(List<NostrFilter> filters, NostrPoolFetchPolicy fetchPolicy) {
        if(isClosed()) throw new IllegalStateException("AdClient is closing");

        // we will use the maximum limit from the filter to autogenerate a fetch policy if one is not provided
        int maxLimit=0;
        for(NostrFilter filter:filters){
            if(filter.getLimit()!=null&&filter.getLimit()>maxLimit){
                maxLimit=filter.getLimit();
            }
        }

        AsyncTask<List<AdBidEvent>> nn=getPool().fetch(
            filters,
            fetchPolicy!=null?
                fetchPolicy:
                // we are going to early stop as soon as we have up to maxLimit events or we receive an eose for every relay
                NostrWaitForEventFetchPolicy.get(e -> true,maxLimit>0?maxLimit:10,true)
        ).then(events -> {
            // turn all the events into bids
            List<AsyncTask<AdBidEvent>> bids=new ArrayList<>();
            for(SignedNostrEvent event:events){
                try{
                    AsyncTask<AdBidEvent> bid=asBid(event);
                    if(bids!=null){
                        bids.add(bid);
                    }
                }catch(Exception e){
                    logger.log(Level.WARNING,"Error processing event: "+event.getId(),e);
                }
            }
            return bids;
        }).compose(negs -> {
            return NGEPlatform.get().awaitAll(negs);
        });
        return nn;
    }

    /**
     * Convert a raw signed nostr event to a Ad bid event, when possible.
     */
    @Override
    protected AsyncTask<AdBidEvent> asBid(SignedNostrEvent event) {
        return super.asBid(event).compose(bidding -> {
            return getSigner().getPublicKey().then(pubkey -> {
                if(bidding.getPubkey().equals(pubkey)){
                    logger.finer("Skipping bid from self: "+bidding.getId());
                    return null; // skip bids from self
                }
                List<NostrPublicKey> tgs=bidding.getTargetedOfferers();

                // workaround https://github.com/hoytech/strfry/issues/150 with client-side
                // filtering
                if(tgs!=null&&!tgs.contains(pubkey)){
                    logger.finer("Skipping bid not targeted to this offerer: "+bidding.getId());
                    return null; // skip bids not targeted to this offerer
                }

                tgs=bidding.getTargetedApps();
                if(tgs!=null&&!tgs.contains(appKey)){
                    logger.finer("Skipping bid not targeted to this app: "+bidding.getId());
                    return null; // skip bids not targeted to this app
                }
                return bidding;
            });
        });

    }

    

 
    /**
     * Open a negotiation for the given bid event manually.
     * This is not needed when using {@link #loadNextAd(Adspace)}, but can be used to handle
     * bids and offers manually by calling {@link OffererNegotiationHandler#makeOffer()} on the negotiation handler returned.
     * 
     * 
     * @param event the signed nostr event representing the bid to open a negotiation for
     * @param listener the listener to notify when the negotiation is ready, can be null
     * @return an AsyncTask that will complete with the AdOffererNegotiationHandler instance
     * @throws IllegalStateException if the AdClient is closing
     * @throws IllegalArgumentException if the event is not a valid AdBidEvent
     */
    public AsyncTask<NegotiationHandler> openNegotiation(SignedNostrEvent event, OffererNegotiationHandler.OfferListener listener) {
        if(isClosed()) throw new IllegalStateException("AdClient is closing");
        return asBid(event).then(bid -> {
            if(bid==null) return null; // skip invalid bids
            return new OffererNegotiationHandler(appKey,getPool(),getSigner(),bid,getMaxDiff());
        }).compose(neg -> {   
            // load the initial penalty for the negotiation       
            return penaltyStorage.get(neg.getBidEvent()).then(penalty -> {
                neg.setCounterpartyPenalty(penalty);
                if(penalty>0){
                    logger.finer("Setting counterparty penalty for "+neg.getBidEvent().getId()+": "+penalty);
                }
                return neg;
            });
        }).then(neg -> {
            // add the listener if provided
            if(listener!=null) neg.addListener(listener);
            // we register the negotiation, the parent class will track and close it when needed (eg. if it expires)
            registerNegotiation(neg);
            return neg;
        });
    }

}
