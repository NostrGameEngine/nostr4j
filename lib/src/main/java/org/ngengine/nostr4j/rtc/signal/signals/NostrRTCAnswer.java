package org.ngengine.nostr4j.rtc.signal.signals;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.NostrRTCPeer;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRTCAnswer implements NostrRTCSignal{
    private static final long serialVersionUID = 1L;

    private final NostrPublicKey pubkey;

    private final Map<String, Object> map;
    private final String sdp;
    private final String turnServer;
    private transient NostrRTCPeer peerInfo;

    public NostrRTCAnswer(NostrPublicKey pubkey, String sdp, String turnServer, Map<String, Object> misc) {
        this.pubkey = pubkey;
        this.sdp = sdp;
        this.turnServer =  turnServer;  
        HashMap<String, Object> map = new HashMap<>();
        if (misc != null && !misc.isEmpty()) {
            map.putAll(misc);
        }
        map.put("sdp", this.sdp);
        if(turnServer != null && !turnServer.isEmpty()){
            map.put("turn", turnServer);
        } else{
            map.remove("turn");
        }
        this.map = Collections.unmodifiableMap(map);
    }

    public NostrRTCAnswer(NostrPublicKey pubkey, Map<String, Object> map) {
        this(
            pubkey,
            NostrUtils.safeString(map.get("sdp")),
            NostrUtils.safeString(map.get("turn")),
            map
        );         
    }

    public String getSdp() {
        return this.sdp;
    }

    public String getTurnServers() {
        return this.turnServer;
    }

    public Map<String, Object> get() {
        return this.map;
    }

 
    public NostrRTCPeer getPeerInfo(){
        if(peerInfo!=null) return peerInfo;
        peerInfo=new NostrRTCPeer(pubkey, this.turnServer, this.map);
        return peerInfo;        
    }
    
}
