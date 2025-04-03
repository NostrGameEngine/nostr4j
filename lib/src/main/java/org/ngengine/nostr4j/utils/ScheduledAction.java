package org.ngengine.nostr4j.utils;

public class ScheduledAction {
    public final long timestamp;
    public final Runnable action;
    
    public ScheduledAction(long timestamp, Runnable action) {
        this.timestamp = timestamp;
        this.action = action;
    }
}
