package org.ngengine.nostr4j.ads;

import java.util.List;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.ads.negotiation.SdanBailEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanNegotiationEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPowNegotiationEvent;
import org.ngengine.platform.AsyncTask;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Logger;

import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;

public abstract class SdanNegotiation {
    private static final Logger logger = Logger.getLogger(SdanNegotiation.class.getName());
    public static interface Listener {
         void onBail(SdanNegotiation neg, SdanBailEvent event);
    }
 
    protected final NostrPool pool;
    protected final int maxDiff;
    protected final int penaltyIncrease;
    protected final SdanBidEvent bidding;
    
    protected final NostrPublicKey appKey;
    protected final Function<NostrPublicKey, Number> initialPenaltyProvider;
    protected final NostrSigner signer;
    protected final List<Listener> listeners = new CopyOnWriteArrayList<>();


    protected SdanOfferEvent offer = null; // the offer we are negotiating on
    protected int penaltyAppliedToTheCounterparty = 0;
    protected int penaltyAppliedToUs = 0;
    protected AsyncTask<NostrSubscription> sub;
    protected volatile boolean closed = false;
 
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    protected SdanNegotiation(
        Function<NostrPublicKey, Number> initialPenaltyProvider,
        NostrPublicKey appKey,
        NostrPool pool,
        NostrSigner signer,
        SdanBidEvent bidding,
        int maxDiff,
        int penaltyIncrease
    ) {
        this.signer = signer;
        this.initialPenaltyProvider = initialPenaltyProvider;
        this.appKey = appKey;
        this.pool = pool;
        this.bidding = bidding;
        this.maxDiff = maxDiff;
        this.penaltyIncrease = penaltyIncrease;
    }

    void init(SdanOfferEvent offer) {
        this.offer = offer;
        this.sub = signer.getPublicKey().then(pubkey -> {
            if(closed)return null;

            NostrSubscription sub = pool.subscribe(
                    new NostrFilter()
                            .withKind(SdanNegotiationEvent.KIND)
                            .withTag("p", pubkey.asHex())
                            .withAuthor(pubkey.equals(bidding.getPubkey()) ? offer.getPubkey() : bidding.getPubkey())
                            .withTag("d", offer.getId())
                            );

            sub.addEventListener((event, stored) -> {
                SdanNegotiationEvent.cast(signer, event, offer).then(ev -> {
                    onEvent(ev);
                    return null;
                });
            });
            sub.open();

            return sub;
        });
    }

    public void close() {
        closed=true;
        if(sub==null)return;
        sub.then(NostrSubscription::close);
    }

    public AsyncTask<SdanBailEvent> bail( SdanBailEvent.Reason reason) {
        SdanBailEvent.Builder builder = new SdanBailEvent.Builder();
        builder.withReason(reason);
        return builder.build(signer, offer);
    }

    protected void onEvent(SdanNegotiationEvent event) {
      
            try {
                if (event instanceof SdanPowNegotiationEvent) {
                    SdanPowNegotiationEvent powEvent = (SdanPowNegotiationEvent) event;
                    if (powEvent.getRequestedDifficultyToRespond() > penaltyAppliedToUs) {
                        int p = powEvent.getRequestedDifficultyToRespond();
                        if (p < 0) p = 0;
                        if (p > maxDiff) {
                            throw new Exception("Too difficult");
                        }
                        this.penaltyAppliedToUs = p;
                    }
                }

                if (event instanceof SdanBailEvent) {
                    SdanBailEvent bailEvent = (SdanBailEvent) event;
                    for (Listener listener : listeners) {
                        listener.onBail(this, bailEvent);
                    }
                    close();
                }
            } catch (Exception e) {
                throw new RuntimeException("Error processing event: " + event.getId(), e);
            }
    
    }
 
  
 
    /**
     * Punish cheating
     * @param increase
     * @throws Exception
     */
    public int punishCounterparty( ) throws Exception {    
        this.penaltyAppliedToTheCounterparty += penaltyIncrease;
        if (this.penaltyAppliedToTheCounterparty > maxDiff) {
            this.penaltyAppliedToTheCounterparty = maxDiff;
        }
        return this.penaltyAppliedToTheCounterparty;
    }
    
 
}
