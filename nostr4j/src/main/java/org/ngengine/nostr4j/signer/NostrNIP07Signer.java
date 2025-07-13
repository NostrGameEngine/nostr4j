package org.ngengine.nostr4j.signer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

public class NostrNIP07Signer implements NostrSigner {
    public NostrNIP07Signer(){
              
    }

    @Override
    public AsyncTask<SignedNostrEvent> sign(UnsignedNostrEvent event) {
        NGEPlatform p=NGEPlatform.get();
        
        Map<String, Object> params = new HashMap<>();
        params.put("kind", event.getKind());
        params.put("content", event.getContent());
        params.put("tags", event.getTagRows());
        params.put("created_at", event.getCreatedAt().getEpochSecond());
        
        return p.wrapPromise((res, rej) -> {
            p.callFunction("window.nostr.signEvent",List.of(params),(result) -> {
                SignedNostrEvent signed =  new SignedNostrEvent((Map<String,Object>)result);
                res.accept(signed);
            },(err) -> {
                rej.accept(err);
            });
        });
    }

    private String getEncFun(EncryptAlgo algo, String type) {
        String fun = "window.nostr.";
        switch (algo) {
            case NIP04:
                fun = fun + "nip04";
            default:
            case NIP44:
                fun = fun + "nip44";        
        }
         return fun + "." + type;

    }

    @Override
    public AsyncTask<String> encrypt(String message, NostrPublicKey publicKey, EncryptAlgo algo) {
        NGEPlatform p = NGEPlatform.get();
        return p.wrapPromise((res, rej) -> {
            p.callFunction(getEncFun(algo, "encrypt"), List.of(publicKey.asHex(),message), (result) -> {
                res.accept(result.toString());
            }, (err) -> {
                rej.accept(err);
            });
        });
    }

    @Override
    public AsyncTask<String> decrypt(String message, NostrPublicKey publicKey, EncryptAlgo algo) {
        NGEPlatform p = NGEPlatform.get();
        return p.wrapPromise((res, rej) -> {
            p.callFunction(getEncFun(algo, "decrypt"), List.of(publicKey.asHex(),message), (result) -> {
                res.accept(result.toString());
            }, (err) -> {
                rej.accept(err);
            });
        });
    }

    @Override
    public AsyncTask<NostrPublicKey> getPublicKey() {
        NGEPlatform p = NGEPlatform.get();
        return p.wrapPromise((res,rej)->{
            p.callFunction("window.nostr.getPublicKey", List.of(), (result)->{
                res.accept(NostrPublicKey.fromHex(result.toString()));
            }, (err)->{
                rej.accept(err);
            });            
        });
    }

    @Override
    public AsyncTask<NostrSigner> close() {
        return NGEPlatform.get().wrapPromise((res, rej) -> {
            res.accept(this);
        });
    }

    @Override
    public AsyncTask<Boolean> isAvailable(){
        NGEPlatform p = NGEPlatform.get();
        return p.wrapPromise((res, rej) -> {
            if(!p.getPlatformName().contains("(browser)")){
                res.accept(false);
                return;
            }
            p.canCallFunction("window.nostr.getPublicKey",  (result) -> {
                res.accept((boolean) result);
            });
        });
    }

    
}
