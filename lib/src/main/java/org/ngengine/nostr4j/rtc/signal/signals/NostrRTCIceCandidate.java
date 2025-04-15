package org.ngengine.nostr4j.rtc.signal.signals;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.ngengine.nostr4j.transport.NostrMessageFragment;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRTCIceCandidate implements NostrRTCSignal{
    private static final long serialVersionUID = 1L;

    private final Map<String, Object> map;
    private final Collection<String> candidates; 

 
    public NostrRTCIceCandidate(Collection<String> candidates,  Map<String, Object> misc) {
        this.candidates =  Collections.unmodifiableCollection(candidates);
        HashMap<String, Object> map = new HashMap<>();
        if (misc != null && !misc.isEmpty()) {
            map.putAll(misc);
        }
        map.put("candidates", this.candidates);
        this.map = Collections.unmodifiableMap(map);
    }

    public NostrRTCIceCandidate(Map<String, Object> map) {
        this(
            Arrays.asList(NostrUtils.safeStringArray(map.get("candidates"))),
            map
        );         
    }

    public Collection<String> getCandidates() {
        return this.candidates;
    }

 
    public Map<String, Object> get() {
        return this.map;
    }
}
