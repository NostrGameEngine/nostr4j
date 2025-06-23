package org.ngengine.nostr4j.ads;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.ads.negotiation.SdanAcceptOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanBailEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanNegotiationEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPayoutEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPowNegotiationEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip09.Nip09EventDeletion;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostr4j.utils.UniqueId;
import org.ngengine.platform.AsyncTask;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public class SdanClient {
    private final static Logger logger = Logger.getLogger(SdanClient.class.getName());
    protected final NostrSigner signer;
    protected final int maxDiff;
    protected final int penaltyIncrease;
    protected final NostrPool pool;
    protected final SdanTaxonomy taxonomy;
    protected final NostrPublicKey appKey;
    private final  List<NostrSubscription> activeSubscriptions = new CopyOnWriteArrayList<>();
    private final Map<SdanBidEvent, NostrSubscription> activeBids = new ConcurrentHashMap<>();

    protected Function<NostrPublicKey, Number> initialPenaltyProvider = (pubkey) -> {
        return 0;
    };

    public void setInitialPenaltyProvider(Function<NostrPublicKey, Number> initialPenaltyprovider) {
        this.initialPenaltyProvider = initialPenaltyprovider;
    }

    public SdanClient(
            NostrPool pool,
            NostrPublicKey appKey,
            NostrSigner signer) throws IOException {
        this(pool, signer, appKey, new SdanTaxonomy(), 32, 1);
    }
    public SdanClient(
        NostrPool pool, 
        NostrSigner signer,
        NostrPublicKey appKey,
        SdanTaxonomy taxonomy
    ) {
        this(pool, signer, appKey, taxonomy, 32, 1);
    }

    public SdanClient(
        NostrPool pool, 
        NostrSigner signer,    
        NostrPublicKey appKey,
        SdanTaxonomy taxonomy,
        int maxDiff,
        int penaltyIncrease
    ) {
        this.signer = signer;
        this.maxDiff = maxDiff;
        this.penaltyIncrease = penaltyIncrease;
        this.pool = pool;
        this.taxonomy = taxonomy;
        this.appKey = appKey;
    }


    public SdanTaxonomy getTaxonomy() {
        return taxonomy;
    }

   public NostrSubscription listenForBids(
        List<NostrFilter> filters, 
        SdanOffererSideNegotiation.OfferListener listener
    ) {

        NostrSubscription sub = pool.subscribe(filters);
        sub.addEventListener((event,stored)->{
            SdanBidEvent bidding = new SdanBidEvent(taxonomy, (SignedNostrEvent) event);
            if(!bidding.isValid()){
               logger.fine("Invalid bidding event: " + bidding.getId());
                return;
            }

            signer.getPublicKey().then(pubkey->{
                try{
                    if (bidding.getPubkey().equals(pubkey)) {
                        logger.fine("Skipping bid from self: " + bidding.getId());
                        return null; // skip bids from self
                    }
                    List<NostrPublicKey> tgs = bidding.getTargetedOfferers();

                    // workaround  https://github.com/hoytech/strfry/issues/150 with client-side filtering 
                    if(tgs != null && !tgs.contains(pubkey)) {
                        logger.fine("Skipping bid not targeted to this offerer: " + bidding.getId());
                        return null; // skip bids not targeted to this offerer
                    }

                    tgs = bidding.getTargetedApps();
                    if(tgs != null && !tgs.contains(appKey)) {
                        logger.fine("Skipping bid not targeted to this app: " + bidding.getId());
                        return null; // skip bids not targeted to this app
                    }

                    SdanOffererSideNegotiation neg = new SdanOffererSideNegotiation(
                        initialPenaltyProvider,
                        appKey,
                        pool,
                        signer,
                        bidding,
                        maxDiff,
                        penaltyIncrease
                    );
                    neg.addListener(listener);
                    neg.init();        
                    return null;
                } catch (Exception e) {
                    logger.log(Level.FINE,"Error processing bid",e);
                    return null;
                }
            });            
        });
        sub.addCloseListener(reason->{
            activeSubscriptions.remove(sub);
        });
        activeSubscriptions.add(sub);
        sub.open();
        return sub;
    }

 
    public AsyncTask<SdanBidEvent> newBid(
        // meta
        @Nonnull String description,
        @Nullable List<SdanTaxonomy.Term> categories,
        @Nullable List<String> languages,
        @Nullable List<NostrPublicKey> offerersWhitelist,
        @Nullable List<NostrPublicKey> appsWhitelist,
        
        // ad payload
        @Nonnull SdanMimeType mimeType,
        @Nonnull String payload,
        @Nonnull SdanSize size,

        // link and call to action
        @Nonnull String link,
        @Nullable String callToAction,
        @Nonnull SdanActionType actionType,

        // bid props
        @Nonnull long bidMsats,
        @Nonnull Duration holdTime,
        @Nullable NostrPublicKey delegate,
        @Nullable Instant expireAt

    ) {
        return newBid(
            UniqueId.getNext(),
            description,
            categories,
            languages,
            offerersWhitelist,
            appsWhitelist,
            mimeType,
            payload,
            size,
            link,
            callToAction,
            actionType,
            bidMsats,
            holdTime,
            delegate,
            expireAt
        );        
    }

    public AsyncTask<SdanBidEvent> newBid(
        // id and meta
        @Nonnull String id,
        @Nonnull String description,
        @Nullable List<SdanTaxonomy.Term> categories,
        @Nullable List<String> languages,
        @Nullable List<NostrPublicKey> offerersWhitelist,        
        @Nullable List<NostrPublicKey> appsWhitelist,        
        
        // ad payload
        @Nonnull SdanMimeType mimeType,
        @Nonnull String payload,
        @Nonnull SdanSize size,

        // link and call to action
        @Nonnull String link,
        @Nullable String callToAction,
        @Nonnull SdanActionType actionType,
        
        // bid props
        @Nonnull long bidMsats,
        @Nonnull Duration holdTime,
        @Nullable NostrPublicKey delegate,
        @Nullable Instant expireAt        

    ) {
        SdanAspectRatio aspectRatio = size.getAspectRatio();
        SdanPriceSlot priceSlot = SdanPriceSlot.fromValue(bidMsats);
        SdanBidEvent.Builder builder = new SdanBidEvent.Builder(getTaxonomy(),id);
        builder.withDescription(description);
        if(categories!=null){
            for(SdanTaxonomy.Term category : categories) {
                builder.withCategory(category);
            }
        }
        if(languages!=null){
            for(String lang : languages) {
                builder.withLanguage(lang);
            }
        }

        if(offerersWhitelist!=null){
            for(NostrPublicKey target : offerersWhitelist) {
                builder.whitelistOfferer(target);
            }
        }
        if(appsWhitelist!=null){
            for(NostrPublicKey target : appsWhitelist) {
                builder.whitelistApp(target);
            }
        }
        builder.withMIMEType(mimeType);
        builder.withPayload(payload);
        builder.withDimensions(size);
        builder.withLink(link);
        if(callToAction!=null) {
            builder.withCallToAction(callToAction);
        }
        builder.withActionType(actionType);
        builder.withBidMsats(bidMsats);
        builder.withHoldTime(holdTime);
        builder.withAspectRatio(aspectRatio);
        builder.withPriceSlot(priceSlot);
        if(expireAt!=null){
            builder.withExpiration(expireAt);
        }

        if(delegate!=null){
            builder.withDelegate(delegate);
        }
 
        return builder.build(signer);
    }

    public AsyncTask<List<AsyncTask<NostrMessageAck>>> publishBid(SdanBidEvent ev){
        return pool.publish(ev);
    }

    public AsyncTask<List<AsyncTask<NostrMessageAck>>> cancelBid(SdanBidEvent ev, String reason) {
        UnsignedNostrEvent cancel = Nip09EventDeletion.createDeletionEvent(reason,ev);
        return this.signer.sign(cancel).compose(signed -> {
            return pool.publish(signed);
        });
    }

    public NostrSubscription handleBid(SdanBidEvent ev,SdanAdvSideNegotiation.AdvListener listener){        
        if(activeBids.containsKey(ev)){
            throw new IllegalStateException("Bid already being handled: " + ev.getId());
        }
        NostrSubscription sub = pool.subscribe(
            new NostrFilter()
                    .withTag("p", ev.getDelegate().asHex())
                    .withKind(SdanNegotiationEvent.KIND)
                    .limit(1)
                    .withTag("d", ev.getId()));

        sub.addEventListener((event, stored) -> {
            SdanNegotiationEvent.cast(signer, event, null).then(nev -> {
                if (nev instanceof SdanOfferEvent) {
                    List<NostrPublicKey> tgs = ev.getTargetedOfferers();
                    if (tgs != null && !tgs.contains(nev.getPubkey())) return null; 
                    tgs = ev.getTargetedApps();
                    if (tgs != null && !tgs.contains(((SdanOfferEvent) nev).getAppPubkey()))  return null;  
                    SdanAdvSideNegotiation neg = new SdanAdvSideNegotiation(
                        this.initialPenaltyProvider,
                        this.appKey,
                        this.pool,
                        this.signer,
                        ev,
                        this.maxDiff,
                        this.penaltyIncrease
                    );     
                    neg.addListener(listener);
                    neg.init((SdanOfferEvent) nev);             
                 }
                return null;
            })
            .catchException(ex->{
                logger.log(Level.WARNING, "Error processing event: " + event.getId(), ex);
                
            });
        });
        activeSubscriptions.add(sub);
        sub.addCloseListener(reason -> {
            activeSubscriptions.remove(sub);
        });
        activeBids.put(ev, sub);
        sub.open();
        return sub;
    }


    public void close(SdanBidEvent ev) {
        close(ev, true);
    }

    public void close(SdanBidEvent ev, boolean cancel) {
        NostrSubscription sub = activeBids.remove(ev);
        if (sub != null) {
            sub.close();
        } else {
            logger.warning("No active bid subscription found for: " + ev.getId());
        }
        if(cancel){
            cancelBid(ev, "Bid closed by client");
        }
    }

    public void close(){
        activeBids.forEach((ev, sub) -> {
            sub.close();
        });
        activeBids.clear();
        activeSubscriptions.clear();
    }
}
