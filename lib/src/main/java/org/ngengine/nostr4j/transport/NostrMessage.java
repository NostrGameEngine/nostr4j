package org.ngengine.nostr4j.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.utils.NostrUtils;

public abstract class NostrMessage extends NostrMessageFragment {
    
    protected abstract String getPrefix();

    protected abstract Collection<Object> getFragments();

    protected Map<String, Object> toMap(){
        throw new UnsupportedOperationException("toMap() not implemented");
    }

    private transient volatile String jsonCache=null;

    protected List<Object> toSerial(){
        Collection<Object> fragments = getFragments();
        List<Object> serial = new ArrayList<>(fragments.size() + 1);
        serial.add(getPrefix());
        for (Object fragment : fragments) {
            if (fragment instanceof NostrMessageFragment) {
                serial.add(((NostrMessageFragment) fragment).toMap());
            } else {
                serial.add(fragment);
            }

        }
        return serial;
    }

    protected  String toJSON() throws Exception{

        if(jsonCache==null){
            Platform platform = NostrUtils.getPlatform();
            Collection<Object> serial = toSerial();
            jsonCache = platform.toJSON(serial);
        }
        return jsonCache;
    }
    
    @Override
    public final String toString(){
        try{
            return toJSON();
        }catch(Exception e){
            return this.getClass().getName() + "@" + Integer.toHexString(this.hashCode());
        }
    }

    public static String toJSON(NostrMessage message) throws Exception{
        return message.toJSON();
    }

    
    public static List<Object> toSerial(NostrMessage message)  {
        return message.toSerial();
    }
    public static NostrMessageAck ack(  
        NostrRelay relay,
        String id, 
        long sentAt,
        BiConsumer<NostrMessageAck, String> successCallback, 
        BiConsumer<NostrMessageAck, String> failureCallback
     ){
        return new NostrMessageAck(relay, id, sentAt, successCallback, failureCallback);
     }
}
