package org.ngengine.nostrads.client.services.display;

import java.util.List;
import java.util.function.Function;

import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdMimeType;
import org.ngengine.nostrads.protocol.types.AdPriceSlot;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public class Adspace{
    private final double ratio;
    private final List<AdMimeType> mimetypes;
    private final Function<AdBidEvent,AsyncTask<Boolean>> showBid;
    private final int numBidsToLoad;
    private final AdPriceSlot priceSlot;

    private Runnable onComplete;
    private List<AdTaxonomy.Term> categories;
    private List<NostrPublicKey> advertisersWhitelist;
    private List<String> languages;
    
    private Function<AdBidEvent,AsyncTask<Boolean>> acceptBid=(bid) -> {
        return NGEPlatform.get().wrapPromise((res, rej) -> {
            res.accept(true); // accept all bids by default
        });
    };

    public Adspace(double ratio,int numBidsToLoad,@Nonnull AdPriceSlot priceSlot,@Nonnull List<AdMimeType> mimetypes,@Nonnull Function<AdBidEvent,AsyncTask<Boolean>> showBid){
        this.ratio=ratio;
        this.mimetypes=mimetypes;
        this.showBid=showBid;
        this.numBidsToLoad=numBidsToLoad;
        this.priceSlot=priceSlot;
    }

    @Override
    public String toString() {
        return "Adspace{"+"ratio="+ratio+", mimetypes="+mimetypes+", numBidsToLoad="+numBidsToLoad+", priceSlot="+priceSlot+", categories="+categories+", languages="+languages+", advertisersWhitelist="+advertisersWhitelist+'}';
    }

    @Override
    public boolean equals(Object o) {
        if(this==o) return true;
        if(!(o instanceof Adspace)) return false;
        Adspace adspace=(Adspace)o;
        return Double.compare(adspace.ratio,ratio)==0&&numBidsToLoad==adspace.numBidsToLoad&&mimetypes.equals(adspace.mimetypes)&&priceSlot==adspace.priceSlot&&(categories!=null?categories.equals(adspace.categories):adspace.categories==null)&&(languages!=null?languages.equals(adspace.languages):adspace.languages==null)&&(advertisersWhitelist!=null?advertisersWhitelist.equals(adspace.advertisersWhitelist):adspace.advertisersWhitelist==null);
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        temp=Double.doubleToLongBits(ratio);
        result=(int)(temp^(temp>>>32));
        result=31*result+mimetypes.hashCode();
        result=31*result+numBidsToLoad;
        result=31*result+priceSlot.hashCode();
        result=31*result+(categories!=null?categories.hashCode():0);
        result=31*result+(languages!=null?languages.hashCode():0);
        result=31*result+(advertisersWhitelist!=null?advertisersWhitelist.hashCode():0);
        return result;
    }

    public double getRatio() {
        return ratio;
    }

    @Nullable
    public List<AdTaxonomy.Term> getCategories() {
        return categories;
    }

    @Nonnull
    public List<AdMimeType> getMimeTypes() {
        return mimetypes;
    }

    @Nullable
    public List<String> getLanguages() {
        return languages;
    }

    public int getNumBidsToLoad() {
        return numBidsToLoad;
    }

    @Nonnull
    public AdPriceSlot getPriceSlot() {
        return priceSlot;
    }

    @Nonnull
    protected Function<AdBidEvent,AsyncTask<Boolean>> getBidFilter() {
        return acceptBid;
    }

    @Nonnull
    protected Function<AdBidEvent,AsyncTask<Boolean>> getShowBidAction() {
        return showBid;
    }

    public Adspace withCategory(@Nonnull AdTaxonomy.Term category) {
        if(categories==null){
            categories=List.of(category);
        }else{
            categories.add(category);
        }
        return this;
    }

    public Adspace withLanguage(@Nonnull String language) {
        if(languages==null){
            languages=List.of(language);
        }else{
            languages.add(language);
        }
        return this;
    }

    public void setBidFilter(@Nullable Function<AdBidEvent,AsyncTask<Boolean>> acceptBid) {
        this.acceptBid=acceptBid !=null? acceptBid : (bid) -> {
            return NGEPlatform.get().wrapPromise((res, rej) -> {
                res.accept(true); // accept all bids by default
            });
        };
    }

    public void setOnCompleteCallback(@Nullable Runnable onComplete) {
        this.onComplete=onComplete;

    }

    public Runnable getOnCompleteCallback() {
        return onComplete;
    }

    public void setAdvertisersWhitelist(@Nullable List<NostrPublicKey> advertisersWhitelist) {
        this.advertisersWhitelist=advertisersWhitelist;

    }

    public List<NostrPublicKey> getAdvertisersWhitelist() {
        return advertisersWhitelist;
    }
}
