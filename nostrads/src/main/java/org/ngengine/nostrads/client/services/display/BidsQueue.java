package org.ngengine.nostrads.client.services.display;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class BidsQueue {
    public final List<RankedBid> rankedBids = new  ArrayList<>();
    public final AtomicInteger refs = new AtomicInteger(1);
    public Instant newestBidTime = null;
    public Instant oldestBidTime = null;        
}