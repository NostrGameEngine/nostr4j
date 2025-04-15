package org.ngengine.nostr4j.rtc;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.ngengine.nostr4j.keypair.NostrPublicKey;

public class NostrRTCPeer {

    private final NostrPublicKey pubkey;
    private final Map<String, Object> misc;
    private final String turnServer;
    private Instant lastSeen;

    public NostrRTCPeer(NostrPublicKey pubkey, String turnServer, Map<String, Object> misc) {
        this.pubkey = pubkey;
        this.turnServer = turnServer;
        this.misc = new HashMap<>(misc);
    }

    public NostrPublicKey getPubkey() {
        return pubkey;
    }

    public Map<String, Object> getMisc() {
        return misc;
    }

    public String getTurnServer() {
        return turnServer;
    }

    public Instant getLastSeen() {
        if(lastSeen==null) return Instant.now();
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }
}