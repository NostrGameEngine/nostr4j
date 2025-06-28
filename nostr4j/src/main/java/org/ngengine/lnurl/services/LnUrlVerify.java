package org.ngengine.lnurl.services;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.ngengine.lnurl.LnUrlService;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

// lud-21
public class LnUrlVerify implements LnUrlService{
    private final boolean settled;
    private final String pr;
    private final String preimage;
    private transient Map<String, Object> map;
 
    public static boolean isAssignableTo(Map<String, Object> data) {
        return data.containsKey("settled") && data.containsKey("pr");
    }

    public LnUrlVerify(Map<String, Object> data) {
        if(!isAssignableTo(data)) {
            throw new IllegalArgumentException("Data does not match LnUrlVerify structure");
        }
        this.settled = NGEUtils.safeBool(data.get("settled"));
        this.pr = NGEUtils.safeString(data.get("pr"));
        this.preimage = data.containsKey("preimage") ? NGEUtils.safeString(data.get("preimage")) : null;
    }

    @Nullable public String getPreimage() {
        return preimage;
    }

    public String getPr() {
        return pr;
    }

    public boolean isSettled() {
        return settled;
    }
    
    @Override
    public String getName() {
        return "Verify Service";
    }
 

    @Override
    public Map<String, Object> toMap() {
        if(this.map==null){
            HashMap<String,Object> map = new HashMap<>();
            map.put("settled", settled);
            map.put("pr", pr);
            if(preimage != null) {
                map.put("preimage", preimage);
            }
            this.map = Collections.unmodifiableMap(map);
        }
        return this.map;
    }
    
}
