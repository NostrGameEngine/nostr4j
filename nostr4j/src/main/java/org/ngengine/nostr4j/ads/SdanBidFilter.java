package org.ngengine.nostr4j.ads;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.keypair.NostrPublicKey;

/**
 * A nostr filter with helper methods for SDAN
 */
public class SdanBidFilter extends NostrFilter{

    
    public SdanBidFilter() {
        super();
        withKind(SdanBidEvent.KIND);
      
    }

    /**
     * If set the filter will selected only bids that target the specified apps
     * @param apps the list of apps to target
     * @return
     */
    public SdanBidFilter onlyForApp(NostrPublicKey... apps){
        String[] appIds = new String[apps.length];        
        for (int i = 0; i < apps.length; i++) {
            appIds[i] = apps[i].asHex();
        }       
        return (SdanBidFilter)withTag("y", appIds);
    }

    /**
     * If set the filter will selected only bids that are for the specified offerers
     * @param offerers
     * @return
     */
    public SdanBidFilter onlyForOfferers( NostrPublicKey... offerers){
        String[]  offerIds = new String[offerers.length];      
        for (int i = 0; i < offerers.length; i++) {
            offerIds[i] = offerers[i].asHex();
        }      
        return (SdanBidFilter)withTag("p", offerIds);
    }
  

    
    /**
     * If set the filter will selected only bids tat request the specified action type.
     * @param actionType 
     * @return
     */
    public SdanBidFilter withActionType(SdanActionType actionType) {
        withTag("k",actionType.toString());
        return this;
    }

    /**
     * If set the filter will selected only bids with payloads that match the specified MIME type.
     * @param mimeType
     * @return
     */
    public SdanBidFilter withMimeType(SdanMimeType mimeType) {
        withTag("m", mimeType.toString());
        return this;
    }

    /**
     * If set the filter will selected only bids that match the specified category.
     * @param category
     * @return
     */
    public SdanBidFilter withCategory(SdanTaxonomy.Term category) {
        withTag("t", category.id());
        return this;
    }

    /**
     * If set the filter will selected only bids that specifically target the specified language.
     * @param language
     * @return
     */
    public SdanBidFilter withLanguage(String language) {
        withTag("l", language);
        return this;
    }

    /**
     * If set the filter will selected only bids that match the specified size.
     * @param size
     * @return
     */
    public SdanBidFilter withSize(SdanSize size) {
        withTag("s", size.toString());
        return this;
    }
    
    /**
     * If set the filter will selected only bids that match the specified aspect ratio.
     * @param aspectRatio
     * @return
     */
    public SdanBidFilter withAspectRatio(SdanAspectRatio aspectRatio) {
        withTag("S", aspectRatio.toString());
        return this;
    }

    /**
     * If set the filter will selected only bids that offer at least the specified minimum bid amount.
     * @param minBidAmount
     * @return
     */
    public SdanBidFilter withMinBidAmount(SdanPriceSlot minBidAmount) {
        withTag("f", minBidAmount.toString());
        return this;
    }

    
    
}
