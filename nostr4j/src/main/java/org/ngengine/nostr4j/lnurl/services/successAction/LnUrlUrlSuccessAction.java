package org.ngengine.nostr4j.lnurl.services.successAction;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.ngengine.nostr4j.lnurl.services.LnUrlPayRequest;
import org.ngengine.platform.NGEUtils;

public class LnUrlUrlSuccessAction implements LnUrlSuccessAction {
    private static final int MAX_DESCRIPTION_LENGTH = 144;

    private final String description;
    private final URI url;
    private transient Map<String, Object> map;
    private final LnUrlPayRequest payReq;

    public LnUrlUrlSuccessAction(LnUrlPayRequest payReq, String description, String url) {
        this(payReq, description, NGEUtils.safeURI(url));
    }

    public LnUrlUrlSuccessAction(LnUrlPayRequest payReq, String description, URI uri) {
        this.description = description;
        this.url = NGEUtils.safeURI(uri);
        this.payReq = payReq;
        check();
    }

    public LnUrlUrlSuccessAction(LnUrlPayRequest payReq, Map<String, Object> data) {
        if (!isAssignableTo(data)) {
            throw new IllegalArgumentException("Data does not match the structure");
        }
        this.payReq = payReq;
        this.description = NGEUtils.safeString(data.get("description"));
        this.url = NGEUtils.safeURI(data.get("url"));
        check();
    }

    private void check(){
        // check if url domain is the same of payReq callback domain
        if (!payReq.getCallback().getHost().equals(this.url.getHost())) {
            throw new IllegalArgumentException("URL domain does not match the pay request callback domain");
        }
        if(this.description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description cannot exceed "+ MAX_DESCRIPTION_LENGTH+" characters");
        }
    }

    public String getDescription() {
        return description;
    }

    public URI getUrl() {
        return url;
    }
 
    @Override
    public Map<String, Object> toMap() {
        if(this.map==null){
            Map<String, Object> map = new HashMap<>();
            map.put("tag", "url");
            map.put("description", description);
            map.put("url", url.toString());
            this.map = Collections.unmodifiableMap(map);
        }     
        return this.map;     
    }
 
    
    public static boolean isAssignableTo(Map<String, Object> data) {
        return "url".equals(data.get("tag"));
    }
}
