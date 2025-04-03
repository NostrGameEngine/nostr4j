package org.ngengine.nostr4j.transport;

import java.util.HashMap;
import java.util.Map;

import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.utils.NostrUtils;

public abstract class NostrMessageFragment {

    protected abstract Map<String, Object> toMap();
    
    public  String toString(){
        try{
            Platform platform = NostrUtils.getPlatform();
            Map<String,Object> map =toMap();
            return platform.toJSON(map);
        }catch(Exception e){
            return this.getClass().getName() + "@" + Integer.toHexString(this.hashCode());
        }
    }

}
