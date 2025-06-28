package org.ngengine.lnurl.services;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.ngengine.lnurl.LnUrl;
import org.ngengine.lnurl.LnUrlService;
import org.ngengine.lnurl.services.successAction.LnUrlSuccessAction;
import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nullable;

// lud-06, lud-09, lud-11, lud-12, lud-18
public class LnUrlPayRequest implements LnUrlService{ 
    public static final int MAX_METADATA_SIZE = 1024 * 1024; // 1 MB
    public static record Metadata(
        String type,
        Object value
    ){};

    private static final List<String> STR_METADATA_TYPES = List.of(
        "text/plain",
        "text/long-desc",
        "image/png;base64",
        "image/jpeg;base64"
    );

    public static boolean isAssignableTo(Map<String,Object> data) {
        return "payRequest".equals(data.get("tag"));
    }

    private final long maxSendable, minSendable;
    private final int commentAllowed;
    private final List<Metadata> metadata = new ArrayList<>();
    private final URI callback;
    private final LnUrlPayerData payerData;


    public LnUrlPayRequest(
        long maxSendable, 
        long minSendable, 
        URI callback, 
        int commentAllowed, 
        Collection<Metadata> metadata,
        LnUrlPayerData payerData
    ) {
        if (maxSendable < 1 || minSendable < 1 || minSendable > maxSendable) {
            throw new IllegalArgumentException("Invalid sendable range: " + minSendable + " - " + maxSendable);
        }
        this.maxSendable = maxSendable;
        this.minSendable = minSendable;
        this.callback = callback;
        this.commentAllowed = commentAllowed;
        this.metadata.addAll(metadata);
        this.payerData = payerData;
    }
 
    public LnUrlPayRequest(Map<String, Object> data) {
        if (!isAssignableTo(data)) {
            throw new IllegalArgumentException("Data does not represent a pay request");
        }
        this.maxSendable = NGEUtils.safeLong(data.get("maxSendable"));
        this.minSendable = NGEUtils.safeLong(data.get("minSendable"));
        if(this.minSendable < 1 || this.minSendable > this.maxSendable) {
            throw new IllegalArgumentException("Invalid sendable range: " + minSendable + " - " + maxSendable);
        }
        this.callback = NGEUtils.safeURI(data.get("callback"));


        String metaStr = NGEUtils.safeString(data.get("metadata"));
        if(metaStr.isEmpty() || metaStr.length() > MAX_METADATA_SIZE){
            throw new IllegalArgumentException("Metadata is empty or exceeds maximum size of " + MAX_METADATA_SIZE + " bytes");
        }

        List<Object> metaRaw = NGEPlatform.get().fromJSON(metaStr, List.class);
        for(Object o : metaRaw) {
            if(o.getClass().isArray()){
                o = List.of((Object[])o);
            }
            if(o instanceof List lo && lo.size() == 2) {
                String type = NGEUtils.safeString(lo.get(0));
                Object value = lo.get(1);
                if(STR_METADATA_TYPES.contains(type)) {
                    value = NGEUtils.safeString(value);                    
                }                
                metadata.add(new Metadata(type, value));
            }
        }

        Metadata firstMeta = metadata.isEmpty() ? null : metadata.get(0);
        if(firstMeta == null ||! firstMeta.type.equals( "text/plain")){
            throw new IllegalArgumentException("First metadata item must be of type 'text/plain'");
        }

        
        payerData = LnUrlPayerData.fromTemplate((Map<String, Map>) data.get("payerData"));
        commentAllowed = NGEUtils.safeInt(data.get("commentAllowed"));
    }

    public boolean isCommentAllowed() {
        return commentAllowed > 0;
    }

    public int getMaxCommentLength() {
        return commentAllowed;
    }

    public LnUrlPayerData getPayerData() {
        return payerData;
    }

    public boolean canSend(long amount) {
        return amount >= minSendable && amount <= maxSendable;
    }

    public long getMaxSendable() {
        return maxSendable;
    }

    public long getMinSendable() {
        return minSendable;
    }

    public List<Metadata> getMetadata() {
        return metadata;
    }

    public URI getCallback() {
        return callback;
    }

    public URI getCallback(
        long amount, 
        @Nullable String comment, 
        @Nullable LnUrlPayerData payerData
    ){
        if (!canSend(amount)) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " is not within the allowed range: " + minSendable + " - " + maxSendable);
        }
        StringBuilder build = new StringBuilder(getCallback().toString());
        if(build.indexOf("?") < 0) {
            build.append("?");
        } else {
            build.append("&");
        }
        build.append("amount=").append(URLEncoder.encode(""+amount, StandardCharsets.UTF_8));
        if(isCommentAllowed() && comment != null && !comment.isEmpty()) {
            if(comment.length() > getMaxCommentLength()){
                throw new IllegalArgumentException("Comment exceeds maximum length of " + getMaxCommentLength() + " characters");
            }
            build.append("&comment=").append(URLEncoder.encode(comment, StandardCharsets.UTF_8));
        }
        if(payerData != null) {
            build.append("&payerdata=").append(URLEncoder.encode(NGEPlatform.get().toJSON(payerData), StandardCharsets.UTF_8));
        }
        return NGEUtils.safeURI(build.toString());
    }

    public AsyncTask<LnUrlPaymentResponse> fetchInvoice(
        long amount, 
        @Nullable String comment, 
        @Nullable LnUrlPayerData payerData        
    ) throws Exception {
        return fetchInvoice(amount, comment, payerData, LnUrl.DEFAULT_TIMEOUT, null);
    }

    public AsyncTask<LnUrlPaymentResponse> fetchInvoice(
        long amount, 
        @Nullable String comment, 
        @Nullable LnUrlPayerData payerData,
        @Nullable Duration timeout,
        @Nullable Map<String, String> headers
    ) throws Exception {
        URI callback = getCallback(amount, comment, payerData);
        return NGEPlatform.get().httpGet(callback.toString(), timeout, headers).then(body->{
            Map<String,Object> map = NGEPlatform.get().fromJSON(body,Map.class);
            if(LnUrlException.isAssignableTo(map)){
                throw new RuntimeException(new LnUrlException(map));
            }
            if(LnUrlPaymentResponse.isAssignableTo(map)) {
                LnUrlPaymentResponse response = new LnUrlPaymentResponse(map);
                return response;
            } 
            throw new RuntimeException(new LnUrlException(LnUrlException.Status.INVALID, "Invalid LNURL payment response: " + body));
        });
        
    }

    @Override
    public String getName() {
        return "LNURL Pay Request";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("tag", "payRequest");
        map.put("maxSendable", maxSendable);
        map.put("minSendable", minSendable);
        map.put("callback", callback.toString());
        if (commentAllowed > 0) {
            map.put("commentAllowed", commentAllowed);
        }  
        if (!metadata.isEmpty()) {
            List<List<Object>> metaList = new ArrayList<>();
            for (Metadata meta : metadata) {
                List<Object> item = new ArrayList<>();
                item.add(meta.type);
                item.add(meta.value);
                metaList.add(item);
            }
            map.put("metadata", NGEPlatform.get().toJSON(metaList));
        }
        if (payerData != null) {
            Map<String, Map> payerDataTemplate = new HashMap<>();
            for(String key : payerData.keySet()) {
                Map<String, Object> field = new HashMap<>();
                field.put("mandatory", payerData.isRequired(key));
                payerDataTemplate.put(key, field);
            }
            map.put("payerData", payerDataTemplate);
        }
            
        return map;
    }
 

}
