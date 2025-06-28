package org.ngengine.lnurl.services.successAction;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.ngengine.platform.NGEUtils;

public class LnUrlAESSuccessAction implements LnUrlSuccessAction{
    private static final int MAX_DESCRIPTION_LENGTH = 144;
    private static final int MAX_CIPHERTEXT_LENGTH = 1024 * 4; // 4 KB
    private static final int IV_LENGTH = 24;  

    private final String description;
    private final String ciphertext;
    private final String iv;

    private transient Map<String, Object> map;

    public LnUrlAESSuccessAction(
            String description,
            String ciphertext,
            String iv
    ) {
        this.description = description;
        this.ciphertext = ciphertext;
        this.iv = iv;
        check();
    }

    public LnUrlAESSuccessAction(Map<String, Object> data) {
        if (!isAssignableTo(data)) {
            throw new IllegalArgumentException("Data does not match LnUrlAESSuccessAction structure");
        }
        this.description = NGEUtils.safeString(data.get("description"));
        this.ciphertext = NGEUtils.safeString(data.get("ciphertext"));
        this.iv = NGEUtils.safeString(data.get("iv"));
        check();
    }

    private void check() {      
        if (this.description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description cannot exceed 144 characters");
        }
        if (this.ciphertext.length() > MAX_CIPHERTEXT_LENGTH) {
            throw new IllegalArgumentException("Ciphertext cannot exceed " + MAX_CIPHERTEXT_LENGTH + " bytes");
        }
        if (this.iv.length() != IV_LENGTH) {
            throw new IllegalArgumentException("IV must be exactly " + IV_LENGTH + " bytes long");
        }

    }
    public static boolean isAssignableTo(Map<String, Object> data) {
        return "aes".equals(data.get("tag")) ;
    }

    public String getDescription() {
        return description;
    }

    public String getCiphertext() {
        return ciphertext;
    }
    
    public String getIv() {
        return iv;
    }

    @Override
    public Map<String, Object> toMap() {
        if(this.map == null) {
            Map<String, Object> map = new HashMap<>();
            map.put("tag", "aes");
            map.put("description", description);
            map.put("ciphertext", ciphertext);
            map.put("iv", iv);
            this.map = Collections.unmodifiableMap(map);
        }
        return this.map;
    }


}