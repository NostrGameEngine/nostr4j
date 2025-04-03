package org.ngengine.nostr4j.listeners;

import org.ngengine.nostr4j.NostrRelay;

public interface NostrNoticeListener {
    public void onNotice(NostrRelay relay, String message);
}
