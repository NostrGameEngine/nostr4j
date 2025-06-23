package org.ngengine.nostr4j.lnurl.services;

import java.util.Map;

import org.ngengine.nostr4j.lnurl.LnUrlService;
import org.ngengine.platform.NGEUtils;

public class LnUrlException extends Exception implements LnUrlService {

    public enum Status {
        OK, ERROR, NOT_FOUND, INVALID
    }

    private final Status status;
    private transient Map<String, Object> map;

    public LnUrlException(Status status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public LnUrlException(Status status, String message) {
        super(message);
        this.status = status;
    }

    public LnUrlException(Map<String, Object> data) {
        super(NGEUtils.safeString(data.get("reason")));
        String statusStr = NGEUtils.safeString(data.get("status"));
        this.status = Status.valueOf(statusStr != null ? statusStr.toUpperCase() : "ERROR");
    }
    
    @Override
    public String getMessage() {
        return "LnUrlServiceException: " + super.getMessage() + " (Status: " + status + ")";
    }

    public static boolean isAssignableTo(Map<String,Object> data) {
        return "ERROR".equals(data.get("status"));
    }

    @Override
    public Map<String, Object> toMap() {
        if (this.map == null) {
            this.map = Map.of(
                "status", status.name(),
                "reason", getMessage()
            );
        }
        return this.map;
    }

    @Override
    public String getName() {
        return "LnUrl Service Exception";
    }

  
    
}
