package org.ngengine.nostr4j.ads;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.NGEPlatform;

public abstract class SdanEvent extends SignedNostrEvent{
    private final static Logger logger = Logger.getLogger(SdanEvent.class.getName());
    protected Map<String, Object> data;

    protected SdanEvent(Map<String, Object> map, Map<String, Object> data    ) {
        super(map);
        if(data!=null){
            this.data = data;
        }
    }



    protected Object getData(String key, boolean required) {
        if (data == null) {
            data = NGEPlatform.get().fromJSON(getContent(), Map.class);
        }
        Object v = data.get(key);
        if(required && v == null) {
            throw new IllegalArgumentException("Required data key '" + key + "' is missing in the event data");
        }
        return v;
    }

    protected String getTagData(String key, boolean required) {
        TagValue data = getFirstTag(key);
        if(data==null&&required){
            throw new IllegalArgumentException("Required tag '" + key + "' is missing in the event tags");
        }
        return data== null?null:data.get(0);
    }

    
     public void checkValid() throws Exception {
        if(getExpiration()!=null && getExpiration().isBefore(Instant.now())){
            throw new Exception("Event has expired: " + getExpiration());
        }      
    }    
    

    public boolean isValid(){
        try {
            checkValid();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

}
