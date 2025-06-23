package org.ngengine.nostr4j.ads;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import jakarta.annotation.Nullable;

public class SdanBidEvent extends SdanEvent{     
    public static final int KIND = 30100; // SDAN Bid kind
    private final SdanTaxonomy taxonomy;
    
    public SdanBidEvent(
        SdanTaxonomy taxonomy, 
        SignedNostrEvent event
    ){
        super(event.toMap(), null);
        this.taxonomy = taxonomy;
    }

    public String getDescription(){
        return NGEUtils.safeString(getData("description", true));
    }

    @Nullable
    public String getContext(){
        Object content = getData("context", false);
        return content == null ? null : NGEUtils.safeString(content);
    }

    public String getPayload(){
        return NGEUtils.safeString(getData("payload", true));
    }

    public String getLink(){
        return  NGEUtils.safeString(getData("link", true));
    }

    @Nullable
    public String getCallToAction(){
        Object content = getData("call_to_action", false);
        return content == null ? null : NGEUtils.safeString(content);
    }


    public long getBidMsats(){
        Object bid = getData("bid", true);
        return NGEUtils.safeLong(bid); 
    }

    public Duration getHoldTime(){
        return  NGEUtils.safeDurationInSeconds(getData("hold_time", true));
    }

    public SdanActionType getActionType(){
        return SdanActionType.fromValue(getTagData("k", true));
    }

    public SdanMimeType getMIMEType(){
        return SdanMimeType.fromString(getTagData("m", true));
    }


    @Nullable
    public List<SdanTaxonomy.Term> getCategories() {
        ArrayList<SdanTaxonomy.Term> categories = null;
        for (NostrEvent.TagValue tt : getTag("t")) {
            String t = tt.get(0);
            if( t != null && !t.isEmpty()) {
                if (categories == null) {
                    categories = new ArrayList<>();
                }
                SdanTaxonomy.Term term = taxonomy.getById(NGEUtils.safeString(t));
                if (term != null) {
                    categories.add(term);
                }
            }
        }
        return categories;
    }

    @Nullable 
    public List<String> getLanguages(){
        List<String> langs = null;
        for (NostrEvent.TagValue tt : getTag("l")) {
            String l = tt.get(0);
            if (l != null && !l.isEmpty()) {
                if (langs == null) {
                    langs = new ArrayList<>();
                }
                langs.add(NGEUtils.safeString(l));
            }
        }
        return langs;
    }
    
    @Nullable
    public List<NostrPublicKey> getTargetedApps() {
        List<TagValue> values = getTag("y");
        if(values==null)return null;
        List<NostrPublicKey> targets = null;
        for (NostrEvent.TagValue t : values) {
            String p = t.get(0);
            if (p != null && !p.isEmpty()) {
                if (targets == null) {
                    targets = new ArrayList<>();
                }
                targets.add(NostrPublicKey.fromHex(p));
            }
        }
        return targets;
    }


    @Nullable
    public List<NostrPublicKey> getTargetedOfferers() {
        List<TagValue> values = getTag("p");
        if (values == null)
            return null;
        List<NostrPublicKey> targets = null;
        for (NostrEvent.TagValue t : values) {
            String p = t.get(0);
            if (p != null && !p.isEmpty()) {
                if (targets == null) {
                    targets = new ArrayList<>();
                }
                targets.add(NostrPublicKey.fromHex(p));
            }
        }
        return targets;
    }


    public String getAdId(){
        return NGEUtils.safeString(getTagData("d", true));
    }


   

    /**
     * Returns the pubkey of whom is delegated to handle this bid.
     * ie. the delegation tag is present, or the advertiser pubkey otherwise
     * 
     * @return a {@link NostrPublicKey} to which send the negotiations
     */
    public NostrPublicKey getDelegate(){
        String delegation = getTagData("delegation",false);
        if(delegation != null && !delegation.isEmpty()) {
            return NostrPublicKey.fromHex(delegation);
        }
        return getPubkey();
    }


    public SdanPriceSlot getPriceSlot() {
        return SdanPriceSlot.fromString(getTagData("f", true));
    }

    public SdanAspectRatio getAspectRatio() {
        return SdanAspectRatio.fromString(getTagData("S", true));        
    }

    public SdanSize getDimensions(){
        return SdanSize.fromString(getTagData("s", true));
    }

    

    @Override
    public void checkValid() throws Exception{
        super.checkValid();
        getDescription();
        getPayload();
        getLink();
        getBidMsats();
        getHoldTime();
        getActionType();
        getMIMEType();
        getPriceSlot();
        getAspectRatio();
        getDimensions();
        getAdId();
    }

  




    public static class Builder  {
        private final UnsignedNostrEvent event;
        private final Map<String, Object> content = new HashMap<>();
        private final SdanTaxonomy taxonomy;
 
        
        public Builder(
            SdanTaxonomy taxonomy,
            String adId
        ) {
            this.event = new UnsignedNostrEvent();
            this.event.withKind(KIND);
            this.event.withTag("d", adId);
            this.taxonomy = taxonomy;
                      
        }

        public Builder withDescription(String description) {
            this.content.put("description", description);
            return this;
        }
        public Builder withContext(String context) {
            this.content.put("context", context);
            return this;
        }
        public Builder withPayload(String payload) {
            this.content.put("payload", payload);
            return this;
        }
        public Builder withLink(String link) {
            this.content.put("link", link);
            return this;
        }
        public Builder withCallToAction(String callToAction) {
            this.content.put("call_to_action", callToAction);
            return this;
        }
        public Builder withBidMsats(long bidMsats) {
            this.content.put("bid", bidMsats);
            return this;
        }
        public Builder withHoldTime(Duration holdTime) {
            if(holdTime.isNegative()) throw new IllegalArgumentException("Hold time cannot be negative");
            this.content.put("hold_time", holdTime.getSeconds());
            return this;
        }
        public Builder withActionType(SdanActionType actionType) {
            this.event.withTag("k", actionType.getValue());
            return this;
        }
        public Builder withMIMEType(SdanMimeType mimeType) {
            this.event.withTag("m", mimeType.toString());
            return this;
        }
     
        public Builder withPriceSlot(SdanPriceSlot priceSlot) {
            this.event.withTag("f", priceSlot.toString());
            return this;
        }

        public Builder withAspectRatio(SdanAspectRatio aspectRatio) {
            this.event.withTag("S", aspectRatio.toString());
            return this;
        }

        public Builder withDimensions(SdanSize dimensions) {
            this.event.withTag("s", dimensions.toString());
            return this;
        }

        public Builder withExpiration(Instant expireAt) {
            this.event.withExpiration(expireAt);
            return this;
        }

        public Builder withCategory(SdanTaxonomy.Term category) {
            this.event.withTag("t", category.id());
            return this;
        }
        public Builder withLanguage(String language) {
            this.event.withTag("l", language);
            return this;
        }

        public Builder whitelistOfferer(NostrPublicKey target) {
            this.event.withTag("p", target.asHex());
            return this;
        }

        public Builder whitelistApp(NostrPublicKey target) {
            this.event.withTag("y", target.asHex());
            return this;
        }

        public Builder withDelegate(NostrPublicKey delegate) {
            this.event.withTag("delegation", delegate.asHex());
            return this;
        }
          
        
        public AsyncTask<SdanBidEvent> build(NostrSigner signer) {
       
            event.withContent(NGEPlatform.get().toJSON(content));
            return signer.sign(event).then(signed -> {
                SdanBidEvent sdan = new SdanBidEvent(taxonomy, signed);
                try {
                    sdan.checkValid();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return sdan;
            });            
        }        

        
    }
}