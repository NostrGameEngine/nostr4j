package org.ngengine.nostr4j.event.tracker;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;

import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;

public class ForwardSlidingWindowEventTracker implements EventTracker {
    protected final LinkedList<SignedNostrEvent.Identifier> seenEvents = new LinkedList<SignedNostrEvent.Identifier>();

    protected final int maxTrackedEvents;
    protected final int minTrackedEvents;
    protected final long trackingWindowS;
    protected final long trackingWindowsMarginS;
    protected long cutOffS = 0;

    public ForwardSlidingWindowEventTracker(){
        this(2100, 21, 60, TimeUnit.MINUTES, 30, TimeUnit.MINUTES);
    }

    public ForwardSlidingWindowEventTracker(
        int maxTrackedEvents, 
        int minTrackedEvents, 
        long trackingWindow, 
        TimeUnit trackingWindowTimeUnit,
        long trackingWindowMargin,
        TimeUnit trackingWindowMarginTimeUnit
    ){
        this.maxTrackedEvents = maxTrackedEvents;
        this.minTrackedEvents = minTrackedEvents < 0 ? 0 : minTrackedEvents;
        this.trackingWindowS = trackingWindowTimeUnit.toSeconds(trackingWindow);
        this.trackingWindowsMarginS = trackingWindowMarginTimeUnit.toSeconds(trackingWindowMargin);
    }

    @Override
    public boolean seen(SignedNostrEvent event){
        if(event.getCreatedAt() < cutOffS){
            return true;
        }
        SignedNostrEvent.Identifier newEventId = event.getIdentifier();
        ListIterator<SignedNostrEvent.Identifier> it = seenEvents.listIterator();

        // Check if the event is already seen
        // if it is not, add it to the list (ordered from most recent to oldest)
        while (it.hasNext()) {
            SignedNostrEvent.Identifier seenEventId = it.next();
            if (seenEventId.id.equals(newEventId.id)) {
                return true;
            }
            if(seenEventId.createdAt < newEventId.createdAt){
                it.previous();
                it.add(newEventId);
                update();
                assert checkOrder() : "Events are not in order";
                return false;
            }
            
        }
        // if we reach here, the event is older than all seen events
        // add it to the end of the list
        it.add(newEventId);
        update();        
        assert checkOrder() : "Events are not in order (2)";
        return false;
    }

    protected int count() {
        return seenEvents.size();
    }

    protected long currentTimeSeconds(){
        return System.currentTimeMillis() / 1000;
    }

    protected Collection<SignedNostrEvent.Identifier> getAll() {
        return Collections.unmodifiableCollection(seenEvents);
    }

    protected void update(){
        if(seenEvents.size()<=minTrackedEvents){
            return;
        }

        int toRemove = seenEvents.size() - maxTrackedEvents;
        if(toRemove < 0) toRemove = 0;
        
        
        boolean cutOffUpdate = false;
        
        long t = currentTimeSeconds();
        if(t-cutOffS>trackingWindowS){
            cutOffUpdate = true;
            cutOffS = t - (trackingWindowS - trackingWindowsMarginS);
            assert cutOffS <= currentTimeSeconds()
                    : "Cut off time is in the future " + cutOffS + " > " + currentTimeSeconds();
        }

        assert checkOrder() : "Events are not in order";
        assert toRemove >= 0 && toRemove <= seenEvents.size() && toRemove <= maxTrackedEvents : "Invalid number of events to remove";


        ListIterator<SignedNostrEvent.Identifier> it = seenEvents.listIterator(seenEvents.size());

        // remove the oldest events
        int removed = 0;
        while (it.hasPrevious()  ) {
            SignedNostrEvent.Identifier seenEventId = it.previous();
            if(removed<toRemove){
                it.remove();
                removed++;
                continue;
            }
            if(cutOffUpdate && seenEventId.createdAt < cutOffS){
                it.remove();
                removed++;
                continue;
            }
            break;
        }

        // if we removed some events, we need to update the cut off time
        // to the last event in the list, unless the cut off time is already set
        // to a newer timestamp
        if(toRemove > 0 && seenEvents.size()>0){
            cutOffS = Math.max(cutOffS, seenEvents.getLast().createdAt);
        }
        assert cutOffS <= currentTimeSeconds() || cutOffS >= seenEvents.getLast().createdAt
                : "Cut off time is invalid";

        assert seenEvents.size() <= maxTrackedEvents : "Too many events";
        assert checkOrder() : "Events are not in order";
    }

    protected boolean checkOrder(){
        long last = Long.MAX_VALUE;
        if(seenEvents.isEmpty()){
            return true;
        }

        for(SignedNostrEvent.Identifier id : seenEvents){
            if(id.createdAt > last){
                return false;
            }
            last = id.createdAt;
        }
        return true;
    }

    @Override
    public void tuneFor(NostrSubscription sub) {
    }
}
