package org.ngengine.nostr4j.lnurl.services.successAction;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.ngengine.platform.NGEUtils;

public class LnUrlMessageSuccessAction implements LnUrlSuccessAction {
    private static final int MAX_DESCRIPTION_LENGTH = 144;

    private final String message;
    private transient Map<String, Object> map;

    public LnUrlMessageSuccessAction(String message) {
        this.message = message;
        check();
    }

    public LnUrlMessageSuccessAction(Map<String, Object> data) {
        if (!isAssignableTo(data)) {
            throw new IllegalArgumentException("Data does not match the structure");
        }
        this.message = NGEUtils.safeString(data.get("message"));
        check();
    }


    private void check() { 
        if (this.message.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description cannot exceed "+ MAX_DESCRIPTION_LENGTH+" characters");
        }
    }

    public String getMessage() {
        return message;
    }
 
    @Override
    public Map<String, Object> toMap() {
        if(this.map==null){
            Map<String, Object> map = new HashMap<>();
            map.put("tag", "message");
            map.put("message", message);
            this.map = Collections.unmodifiableMap(map);
        }     
        return this.map;     
    }
 
    
    public static boolean isAssignableTo(Map<String, Object> data) {
        return "message".equals(data.get("tag"));
    }
}
