package org.ngengine.nostrads.client.services;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.client.services.delegate.DelegateService;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.negotiation.AdBailEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * AbstractAdService is the base class for ad services that handle negotiations in the Ad network.
 */
public abstract class AbstractAdService {
    private final static Logger logger = Logger.getLogger(AbstractAdService.class.getName());
    private final NostrSigner signer;
    private int maxDiff = 32;
    private final NostrPool pool;
    private final AdTaxonomy taxonomy;
    protected final AsyncExecutor executor;
    private volatile boolean closed = false;
    private final List<Runnable> closers = new ArrayList<>();
    private final List<NegotiationHandler> activeNegotiations;
    private final Duration negotiationTimeout = Duration.ofMinutes(5);

    /**
     * Constructor for AbstractAdService.
     * @param pool the NostrPool to use for network operations
     * @param signer the NostrSigner to use for signing events
     * @param taxonomy the AdTaxonomy to use for categorizing ads (null to instantiate a default taxonomy)
     * @param penaltyStorage the PenaltyStorage to use for storing and retrieving POW penalties
     */
    protected AbstractAdService(
        @Nonnull NostrPool pool, 
        @Nonnull NostrSigner signer,    
        @Nullable AdTaxonomy taxonomy
    ) {
        if(taxonomy==null){
            taxonomy = new AdTaxonomy();
        }
        this.signer = signer;
        this.pool = pool;
        this.taxonomy = taxonomy;
        this.activeNegotiations=new CopyOnWriteArrayList<>();

        AsyncExecutor updater = NGEPlatform.get().newAsyncExecutor(this.getClass());
        closers.add(NGEPlatform.get().registerFinalizer(this,()->{
            updater.close();
            for(NegotiationHandler negotiation : activeNegotiations) {
                try {
                    if(!negotiation.isCompleted()){
                        negotiation.bail(
                            (this instanceof DelegateService) ?
                                AdBailEvent.Reason.CANCELLED :
                                AdBailEvent.Reason.AD_NOT_DISPLAYED
                        ).then(r->{
                            negotiation.close();
                            return null;
                        });
                    }else{
                        negotiation.close();
                    }
                    
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error closing negotiation: " + negotiation.getBidEvent().getId(), e);
                }
            }
            activeNegotiations.clear();
        }));

        this.executor = updater;
        this.loop();
    }

    /**
     * Registers a negotiation handler to the active negotiations list.
     * Used to track resources and manage negotiation timeouts and cleanup.
     * @param negotiation
     */
    protected void registerNegotiation(NegotiationHandler negotiation) {
       this.activeNegotiations.add(negotiation);
    }

    /**
     * Close the service and clean up resources.
     */
    public void close(){
        closed = true;
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing AdClient", e);
            }
        }
        closers.clear();
    }


  

    /**
     * Set maximum difficulty for POW events before the client will refuse to process them.
     * Default is 32.
     * @param maxDiff
     */
    public void setMaxDiff(int maxDiff) {
        this.maxDiff = maxDiff;
    }

    // loop for cleanup and timeouts
    private void loop(){
        executor.runLater(()->{
            if(closed)return null;          
            for(NegotiationHandler negotiation : activeNegotiations) {
                try {
                    if(negotiation.isCompleted()){
                        negotiation.close();
                    } else if(
                        negotiation.getCreatedAt().plus(negotiationTimeout).isBefore(Instant.now()) ) {
                        logger.fine("Negotiation timeouted: " + negotiation.getBidEvent().getId());
                        // bail the negotiation for timeout
                        negotiation.bail(AdBailEvent.Reason.EXPIRED).await();
                        negotiation.close();
                    }
                    if (negotiation.isClosed()) {
                        activeNegotiations.remove(negotiation);
                        continue;
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error updating negotiation: " + negotiation.getBidEvent().getId(), e);
                }
            }
            loop();
            return null;
        }, 1, TimeUnit.SECONDS);

    }

    /**
     * Get the taxonomy instance used by this service.
     * @return
     */
    protected AdTaxonomy getTaxonomy() {
        return taxonomy;
    }

    /**
     * Convert a raw signed nostr event to a Ad bid event, when possible.
     * @param event the signed nostr event to convert
     * @return an AsyncTask that will complete with the AdBidEvent
     * @throws IllegalStateException if the AdClient is closing
     * @throws IllegalArgumentException if the event is not a valid AdBidEvent
     */
    protected AsyncTask<AdBidEvent> asBid(SignedNostrEvent event) {
        if (closed)
            throw new IllegalStateException("AdClient is closing");
        if(event instanceof AdBidEvent){
            return NGEPlatform.get().wrapPromise((res,rej)->{
                res.accept((AdBidEvent) event);
            });
        }
        AdBidEvent bidding = new AdBidEvent(taxonomy, (SignedNostrEvent) event);
        if (!bidding.isValid()) {
            logger.fine("Invalid bidding event: " + bidding.getId());
            throw new IllegalArgumentException("Invalid bidding event: " + bidding.getId());
        }

        return NGEPlatform.get().wrapPromise((res, rej) -> {
            res.accept(bidding);
        });
    }

    /**
     * Get the NostrSigner instance used by this service.
     * @return
     */
    protected NostrSigner getSigner() {
        return signer;
    }


    /**
     * Get the NostrPool instance used by this service.
     * @return 
     */
    protected NostrPool getPool() {
        return pool;
    }



    /**
     * Register a closer to be executed when the AdClient is closed.
     * This is used to clean up resources and close connections.
     * @param closer
     */
    protected void registerCloser(Runnable closer) {
        if (closers == null) {
            throw new IllegalStateException("AdClient is closing");
        }
        closers.add(closer);
    }


    /**
     * Get the maximum difficulty for POW events before the client will refuse to process them.
     * @return
     */
    protected int getMaxDiff() {
        return maxDiff;
    }

    protected boolean isClosed() {
        return closed;
    }
}
