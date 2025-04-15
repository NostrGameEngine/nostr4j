package org.ngengine.nostr4j.rtc.signal.signals;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.NostrRTCPeer;

public class NostrRTCAnnounce implements NostrRTCSignal {
    private static final long serialVersionUID = 1L;
    
    private final NostrPublicKey publicKey;
    private Instant expireAt;
    private transient NostrRTCPeer peerInfo;

    public NostrRTCAnnounce(
        NostrPublicKey publicKey,
        Instant expireAt
    ) {
        this.publicKey = publicKey;
        this.expireAt = expireAt;
    }
    

    

    public void updateExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
    }


    public Instant getExpireAt() {
        return expireAt;
    }

    public boolean isExpired(Instant now){
        return now.isAfter(expireAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NostrRTCAnnounce)) return false;

        NostrRTCAnnounce that = (NostrRTCAnnounce) o;

        if (!publicKey.equals(that.publicKey)) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return publicKey.hashCode();
    }


    public Map<String, Object> get() {
        return Map.of();
    }
    
    public NostrRTCPeer getPeerInfo(){
        if(peerInfo!=null) return peerInfo;
        peerInfo=new NostrRTCPeer(publicKey, "", Map.of());
        return peerInfo;        
    }
  
}
