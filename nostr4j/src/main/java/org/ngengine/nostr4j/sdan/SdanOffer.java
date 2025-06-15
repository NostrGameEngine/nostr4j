package org.ngengine.nostr4j.sdan;

public class SdanOffer {
    protected SdanBid bid;
    
    public SdanOffer(SdanBid bid){
        this.bid = bid;
    }

    public SdanBid getBid() {
        return bid;
    }
    
}
