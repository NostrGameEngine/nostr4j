package org.ngengine.nostrads.client.advertiser;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip09.Nip09EventDeletion;
import org.ngengine.nostr4j.proto.NostrMessageAck;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostr4j.utils.UniqueId;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdActionType;
import org.ngengine.nostrads.protocol.types.AdAspectRatio;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.protocol.types.AdSize;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * A client for advertisers: it lets create and cancel bids.
 * Bid managment happens through the @{link DelegateService}, so it is not 
 * included in this class.
 */
public class AdvertiserClient {
    private final static Logger logger = Logger.getLogger(AdvertiserClient.class.getName());
    protected final NostrPool pool;
    protected final NostrSigner signer;
    protected final AdTaxonomy taxonomy;

    public AdvertiserClient(
        NostrPool pool, 
        NostrSigner signer,    
        AdTaxonomy taxonomy
    ) {
        this.pool = pool;
        this.signer = signer;
        this.taxonomy = taxonomy;
    }
    
    /**
     * Create a new bid event with a random id.
     * 
     * @param id                Unique identifier in advertiser’s namespace (null to generate a random one).
     * @param description       User‑facing product/service description.
     * @param context           Natural‑language context for optional semantic
     *                          matching (not displayed). Null if not set.
     * @param categories        the categories of the ad content used for
     *                          filtering and targeting (null if not set).
     * @param languages         the language of the ad content as a two‑letter ISO
     *                          639‑1 code (null if not set).
     * @param offerersWhitelist the pubkeys that are authorized to offer to this bid
     *                          (null to not restrict).
     * @param appsWhitelist     the pubkeys of the applications that are authorized
     *                          to offer to this bid (null to not restrict).
     * @param mimeType          the content format of the ad payload
     * @param payload           Ad payload (text or image URL).
     * @param size              the original dimensions of the ad in pixels
     * @param link              URL opened on interaction.
     * @param callToAction      Button/text prompt (e.g., “Buy now”). The offerer
     *                          may choose to display this prompt as part of the ad,
     *                          or omit it entirely. If not set, the offerer may
     *                          apply a default call-to-action of their own
     *                          choosing. (null if not set)
     * @param actionType        the user action that triggers payment
     * @param bidMsats          The amount in msats the advertiser is willing to pay
     *                          for a successful action (number).
     * @param holdTime          Seconds the advertiser will reserve funds after the
     *                          offer has been accepted, while waiting for the
     *                          action to be completed (number).
     * @param delegate          the pubkey that is delegated to manage the ad
     *                          campaign on behalf of the advertiser
     * @param delegatePayload   A payload containing additional information needed
     *                          by the delegate to fulfill its role (null if not
     *                          set).
     * @param expireAtUnix      timestamp (seconds) when bid expires.
     * 
     * @return
     */ 
    public AsyncTask<AdBidEvent> newBid(
        // id and meta
        @Nullable String id,
        @Nonnull String description,
        @Nullable String context,
        @Nullable List<AdTaxonomy.Term> categories,
        @Nullable List<String> languages,
        @Nullable List<NostrPublicKey> offerersWhitelist,        
        @Nullable List<NostrPublicKey> appsWhitelist,        
        
        // ad payload
        @Nonnull AdMimeType mimeType,
        @Nonnull String payload,
        @Nonnull AdSize size,

        // link and call to action
        @Nonnull String link,
        @Nullable String callToAction,
        @Nonnull AdActionType actionType,
        
        // bid props
        @Nonnull long bidMsats,
        @Nonnull Duration holdTime,
        @Nonnull NostrPublicKey delegate,
        @Nullable Map<String, Object> delegatePayload,
        @Nullable Instant expireAt        

    ) {
        if(id==null){
            id=UniqueId.getNext();
        }
        AdAspectRatio aspectRatio = size.getAspectRatio();
        AdPriceSlot priceSlot = AdPriceSlot.fromValue(bidMsats);
        AdBidEvent.BidBuilder builder = new AdBidEvent.BidBuilder(taxonomy,id);
        builder.withDescription(description);
        if(categories!=null){
            for(AdTaxonomy.Term category : categories) {
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
        if(context!=null){
            builder.withContext(context);
        }
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

        builder.withDelegate(delegate, delegatePayload);

 
        return builder.build(signer);
    }


    /**
     * Publish a bid to the network.
     * @param ev the bid event to publish
     * @return a task that will complete when the bid is published.
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> publishBid(AdBidEvent ev){
        return pool.publish(ev);
    }

   
    /**
     * Cancel a bid by creating a deletion event.
     * @param ev the bid event to cancel
     * @param reason the reason for the cancellation
     * @return a task that will complete when the deletion event is published.
     */
    public AsyncTask<List<AsyncTask<NostrMessageAck>>> cancelBid(AdBidEvent ev, String reason) {
        UnsignedNostrEvent cancel = Nip09EventDeletion.createDeletionEvent(reason,ev);
        return this.signer.sign(cancel).compose(signed -> {
            return pool.publish(signed);
        });
    }

 

}
