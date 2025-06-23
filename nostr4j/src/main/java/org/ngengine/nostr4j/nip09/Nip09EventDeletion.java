package org.ngengine.nostr4j.nip09;

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;

public class Nip09EventDeletion {
    public static final int EVENT_DELETION_KIND = 5;

    public static UnsignedNostrEvent createDeletionEvent(String reason, SignedNostrEvent... events){
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(EVENT_DELETION_KIND);
        event.withContent(reason == null ? "" : reason);
        for (SignedNostrEvent e : events) {
            NostrEvent.Coordinates c = e.getCoordinates();
            event.withTag(c.type(), c.coords());
            event.withTag("k", ""+e.getKind());
        }
        return event;
    }


 
    public static UnsignedNostrEvent createDeletionEvent(String reason, NostrEvent.Coordinates... coordinates) {
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.withKind(5);
        event.withContent(reason == null ? "" : reason);
        for (NostrEvent.Coordinates c : coordinates) {
            event.withTag(c.type(), c.coords());
            event.withTag("k", c.kind());
        }
        return event;
    }
}
