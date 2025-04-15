package org.ngengine.nostr4j.rtc;

import java.util.Collection;
import java.util.Map;

import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class NostrRTCLocalPeer  extends NostrRTCPeer{
    private NostrKeyPairSigner signer;
    private Collection<String> stunServers;
    public NostrRTCLocalPeer(
        NostrKeyPair keyPair, 
        Collection<String> stunServers,
        String turnServer, 
        Map<String, Object> misc
    ) {
        super(keyPair.getPublicKey(), turnServer, misc);
        this.signer = new NostrKeyPairSigner(keyPair);
        this.stunServers=stunServers;
    }

    public NostrKeyPairSigner getSigner() {
        return signer;
    }

    public Collection<String> getStunServers() {
        return stunServers;
    }
    
}
