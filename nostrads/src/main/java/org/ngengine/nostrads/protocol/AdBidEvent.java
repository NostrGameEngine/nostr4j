package org.ngengine.nostrads.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.types.AdActionType;
import org.ngengine.nostrads.protocol.types.AdAspectRatio;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.protocol.types.AdSize;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import jakarta.annotation.Nullable;

public class AdBidEvent extends AdEvent{     
    public static final int KIND = 30100; // Ad Bid kind
    private final AdTaxonomy taxonomy;
    
    public AdBidEvent(
        AdTaxonomy taxonomy, 
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

    public AdActionType getActionType(){
        return AdActionType.fromValue(getTagData("k", true));
    }

    public AdMimeType getMIMEType(){
        return AdMimeType.fromString(getTagData("m", true));
    }


    @Nullable
    public List<AdTaxonomy.Term> getCategories() {
        ArrayList<AdTaxonomy.Term> categories = null;
        for (NostrEvent.TagValue tt : getTag("t")) {
            String t = tt.get(0);
            if( t != null && !t.isEmpty()) {
                if (categories == null) {
                    categories = new ArrayList<>();
                }
                AdTaxonomy.Term term = taxonomy.getById(NGEUtils.safeString(t));
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
     * 
     * @return a {@link NostrPublicKey} to which send the negotiations
     */
    public NostrPublicKey getDelegate(){
        String delegation = getTagData("D",true);
        return NostrPublicKey.fromHex(delegation);      
    }


    public AsyncTask<Map<String,Object>> getDecryptedDelegatePayload(NostrSigner signer) {
        TagValue data = getFirstTag("D");
        String encryptedPayload = Objects.requireNonNull(Objects.requireNonNull(data).get(1)); 
        return signer.decrypt(
                encryptedPayload, 
                getPubkey())
                .then(decrypted -> NGEPlatform.get().fromJSON(decrypted, Map.class));
    }


    public AdPriceSlot getPriceSlot() {
        return AdPriceSlot.fromString(getTagData("f", true));
    }

    public AdAspectRatio getAspectRatio() {
        return AdAspectRatio.fromString(getTagData("S", true));        
    }

    public AdSize getDimensions(){
        return AdSize.fromString(getTagData("s", true));
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
        
        getAspectRatio();
        getDimensions();
        getAdId();
        getDelegate();
        AdPriceSlot slot = getPriceSlot();
        if(slot.getValueMsats()>this.getBidMsats()){
            throw new Exception("Invalid bid slot "+slot+" for amount "+this.getBidMsats()+" msats");
        }
    }

  




    public static class BidBuilder  {
        private final UnsignedNostrEvent event;
        private final Map<String, Object> content = new HashMap<>();
        private final AdTaxonomy taxonomy;
        private NostrPublicKey delegate = null;
        private Map<String, Object> delegatePayload = null;
 
        
        public BidBuilder(
            AdTaxonomy taxonomy,
            String adId
        ) {
            this.event = new UnsignedNostrEvent();
            this.event.withKind(KIND);
            this.event.withTag("d", adId);
            this.taxonomy = taxonomy;
                      
        }

        public BidBuilder withDescription(String description) {
            this.content.put("description", description);
            return this;
        }
        public BidBuilder withContext(String context) {
            this.content.put("context", context);
            return this;
        }
        public BidBuilder withPayload(String payload) {
            this.content.put("payload", payload);
            return this;
        }
        public BidBuilder withLink(String link) {
            this.content.put("link", link);
            return this;
        }
        public BidBuilder withCallToAction(String callToAction) {
            this.content.put("call_to_action", callToAction);
            return this;
        }
        public BidBuilder withBidMsats(long bidMsats) {
            this.content.put("bid", bidMsats);
            return this;
        }
        public BidBuilder withHoldTime(Duration holdTime) {
            if(holdTime.isNegative()) throw new IllegalArgumentException("Hold time cannot be negative");
            this.content.put("hold_time", holdTime.getSeconds());
            return this;
        }
        public BidBuilder withActionType(AdActionType actionType) {
            this.event.withTag("k", actionType.getValue());
            return this;
        }
        public BidBuilder withMIMEType(AdMimeType mimeType) {
            this.event.withTag("m", mimeType.toString());
            return this;
        }
     
        public BidBuilder withPriceSlot(AdPriceSlot priceSlot) {
            this.event.withTag("f", priceSlot.toString());
            return this;
        }

        public BidBuilder withAspectRatio(AdAspectRatio aspectRatio) {
            this.event.withTag("S", aspectRatio.toString());
            return this;
        }

        public BidBuilder withDimensions(AdSize dimensions) {
            this.event.withTag("s", dimensions.toString());
            return this;
        }

        public BidBuilder withExpiration(Instant expireAt) {
            this.event.withExpiration(expireAt);
            return this;
        }

        public BidBuilder withCategory(AdTaxonomy.Term category) {
            this.event.withTag("t", category.id());
            return this;
        }
        public BidBuilder withLanguage(String language) {
            this.event.withTag("l", language);
            return this;
        }

        public BidBuilder whitelistOfferer(NostrPublicKey target) {
            this.event.withTag("p", target.asHex());
            return this;
        }

        public BidBuilder whitelistApp(NostrPublicKey target) {
            this.event.withTag("y", target.asHex());
            return this;
        }

        public BidBuilder withDelegate(NostrPublicKey delegate, Map<String,Object> payload) {
            // this.event.withTag("D", delegate.asHex());
            this.delegate = delegate;
            this.delegatePayload = payload;
            return this;
        }
          
        
        public AsyncTask<AdBidEvent> build(NostrSigner signer) {
            return NGEPlatform.get().wrapPromise((res,rej)->{
                if(delegate == null){
                    res.accept(event);
                    return;
                } else if(delegatePayload == null){
                    event.withTag("D", delegate.asHex());
                    res.accept(event);
                } else {
                    String strDelegatePayload = NGEPlatform.get().toJSON(delegatePayload);
                    signer.encrypt(strDelegatePayload, delegate).then(encrypted -> {
                        event.withTag("D", delegate.asHex(), encrypted);
                        res.accept(event);
                        return null;
                    }).catchException(rej::accept);
                }
            }).compose(j->{        
                event.withContent(NGEPlatform.get().toJSON(content));
                return signer.sign(event).then(signed -> {
                    AdBidEvent adevent = new AdBidEvent(taxonomy, signed);
                    try {
                        adevent.checkValid();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return adevent;
                });            
            });
        }        

        
    }
}