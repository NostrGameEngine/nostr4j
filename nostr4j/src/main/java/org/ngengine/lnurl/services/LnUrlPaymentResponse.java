package org.ngengine.lnurl.services;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.ngengine.lnurl.services.successAction.LnUrlAESSuccessAction;
import org.ngengine.lnurl.services.successAction.LnUrlMessageSuccessAction;
import org.ngengine.lnurl.services.successAction.LnUrlSuccessAction;
import org.ngengine.lnurl.services.successAction.LnUrlUrlSuccessAction;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

public class LnUrlPaymentResponse  {
    public static record SuccessActionProcessor(
        Function<Map<String,Object>, Boolean> isAssignableTo,
        BiFunction<LnUrlPayRequest, Map<String, Object>, LnUrlSuccessAction> constructor
    ){};

    private final static List<SuccessActionProcessor> successActionsProcessors = new ArrayList<>();

    public static void registerSuccessActionProcessor(
        SuccessActionProcessor p
    ) {
        successActionsProcessors.add(p);        
    }

    static {
        registerSuccessActionProcessor(new SuccessActionProcessor(LnUrlMessageSuccessAction::isAssignableTo, 
            (req, data) -> new LnUrlMessageSuccessAction(data)));
        registerSuccessActionProcessor(new SuccessActionProcessor(LnUrlUrlSuccessAction::isAssignableTo,
            (req, data) -> new LnUrlUrlSuccessAction(req, data)));
        registerSuccessActionProcessor(new SuccessActionProcessor(LnUrlAESSuccessAction::isAssignableTo,
            (req, data) -> new LnUrlAESSuccessAction( data)));
    }
    

    private final String pr;
    private final boolean disposable;
    private final URI verify;
    private LnUrlSuccessAction successAction;

    public LnUrlPaymentResponse(
        String pr, 
        boolean disposable, 
        URI verify,
        @Nonnull LnUrlSuccessAction successAction
    ) {
        this.pr = pr;
        this.disposable = disposable;
        this.verify = verify;      
        this.successAction = successAction;  
    }

    public LnUrlPaymentResponse(
        Map<String, Object> data
    ) {
        if(!data.containsKey("pr") ) throw new IllegalArgumentException("Data does not contain 'pr' field");
        this.pr = NGEUtils.safeString(data.get("pr"));
        this.disposable = NGEUtils.safeBool(data.getOrDefault("disposable", true));

        String verifyRaw = (String)data.get("verify");
        this.verify = verifyRaw != null ? NGEUtils.safeURI(verifyRaw) : null;

        Map<String,Object> rawSuccessAction = (Map<String,Object>)data.get("successAction");
        if(rawSuccessAction!=null){
            for(int i = successActionsProcessors.size()-1; i >= 0; i--) {
                SuccessActionProcessor p = successActionsProcessors.get(i);
                if(p.isAssignableTo.apply(rawSuccessAction)) {
                    this.successAction = p.constructor.apply(null, rawSuccessAction);
                    break;
                }            
            }
        }
        
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("pr", pr);
        map.put("disposable", disposable);

        if(verify != null) {
            map.put("verify", verify.toString());
        }
        if(successAction != null) {
            map.put("successAction", successAction.toMap());
        }
        return map;
    }

    @Nullable public LnUrlSuccessAction getSuccessAction() {
        return successAction;
    }


    public static boolean isAssignableTo(Map<String, Object> data) {
        return data.containsKey("pr") && !data.containsKey("tag");
    }

    public String getPr() {
        return pr;
    }

    public boolean isDisposable() {
        return disposable;
    }

    public boolean isVerificable() {
        return verify != null;
    }

    public AsyncTask<LnUrlVerify> verify(Duration timeout) throws IOException, InterruptedException {
        if(!isVerificable()) {
            throw new IllegalStateException("This payment response is not verificable");
        }
       return  NGEPlatform.get().httpGet(
            verify.toString(),
            timeout,
            null
       ).then(body->{
            Map<String,Object> data = NGEPlatform.get().fromJSON(body, Map.class);
            if(LnUrlException.isAssignableTo(data)){
                throw new RuntimeException(new LnUrlException(data));
            }
            if(LnUrlVerify.isAssignableTo(data)) {
                return new LnUrlVerify(data);
            } else {
                throw new RuntimeException(new LnUrlException(LnUrlException.Status.INVALID,"Response does not contain a valid verify structure"));
            }
        });            
    }
}