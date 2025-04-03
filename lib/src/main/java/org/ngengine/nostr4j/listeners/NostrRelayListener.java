package org.ngengine.nostr4j.listeners;

import java.util.List;

import org.ngengine.nostr4j.NostrRelay;

public interface NostrRelayListener {
    public void onRelayConnect(NostrRelay relay);
    public void onRelayMessage(NostrRelay relay, List<Object> message);
}
