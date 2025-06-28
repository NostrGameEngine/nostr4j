package org.ngengine.nostr4j.ads.negotiation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.ngengine.nostr4j.ads.SdanEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public abstract class SdanNegotiationEvent extends SdanEvent{
    public static final int KIND = 30101;

    private final SdanOfferEvent offer;
    private final static Logger logger = Logger.getLogger(SdanNegotiationEvent.class.getName());

    public SdanNegotiationEvent(

        SignedNostrEvent event, 
        SdanOfferEvent offer,
        Map<String, Object> content
    ){
        super(event.toMap(), content);
        this.offer = offer;
    }

 
    public NostrPublicKey getCounterparty() {
        return NostrPublicKey.fromHex(NGEUtils.safeString(getTagData("p", true)));

    }

    public String getTargetEvent(){
        return NGEUtils.safeString(getTagData("d",true));
    }

 
    public String getType(){
        Object type = getData("type", true);
        return NGEUtils.safeString(type);
    }
  
    public String getMessage() {
        Object message = getData("message", false);
        return NGEUtils.safeString(message);
    }

   
    public SdanOfferEvent getOffer(){
        return offer;
    }

    @Override
    public void checkValid() throws Exception{
        super.checkValid();
        getType();
        getTargetEvent();
        getCounterparty();        
    }


    public static interface Factory<T extends SdanNegotiationEvent> {
        T create(NostrSigner signer, SignedNostrEvent event, SdanOfferEvent offer, Map<String, Object> unencryptedContent);
    }
    
    public static abstract class Builder<T extends SdanNegotiationEvent> {
        protected final UnsignedNostrEvent event;
        protected final Map<String, Object> content = new HashMap<>();
        protected final Factory<T> factory;
        
        public Builder(
            Factory<T> factory,
            String type
        ) {

        
            this.event = new UnsignedNostrEvent();
            this.event.withKind(KIND);            
            this.content.put("type", type);
            this.factory = factory;
           
        }
 

        public Builder<T> withMessage(String message) {
            content.put("message", message);
            return this;
        }


        public Builder<T> withExpiration(Instant expireAt){
            this.event.withExpiration(expireAt);
            return this;
        }

        public Builder<T> withContent(String key, Object value) {
            content.put(key, value);
            return this;
        }

        
        public AsyncTask<T> build(
            NostrSigner signer, 
            SignedNostrEvent negotiationTarget
        ) {
            
            this.event.withTag("d", negotiationTarget.getId());
        
            return signer.getPublicKey().compose(pubkey->{
                NostrPublicKey counterparty;
                if(negotiationTarget instanceof SdanOfferEvent){
                    SdanOfferEvent offer = (SdanOfferEvent)negotiationTarget;
                    if(pubkey.equals(offer.getPubkey())){
                        counterparty = offer.getCounterparty();
                    } else {
                        counterparty = offer.getPubkey();
                    }
                } else{
                    counterparty = negotiationTarget.getPubkey();
                }

                this.event.withTag("p", counterparty.asHex());

                return signer.encrypt(NGEPlatform.get().toJSON(content), counterparty).compose(encrypted -> {
                    event.withContent(encrypted);
                    return signer.sign(event);
                }).then(signed -> {
                    return this.factory.create(signer, signed, negotiationTarget instanceof SdanOfferEvent ? (SdanOfferEvent)negotiationTarget : null, content);
                }).then(ev -> {
                    return (T) ev;
                });   
               
            });
        }        

        
    }

    

    @SuppressWarnings("unchecked")
    public static <T extends SdanNegotiationEvent> AsyncTask<T> cast(
        NostrSigner signer,
        SignedNostrEvent e,
        SdanOfferEvent offer
        
    ) {
        return ( AsyncTask<T> )signer.decrypt(e.getContent(), e.getPubkey()).then(decrypted -> {
            Map<String, Object> content = NGEPlatform.get().fromJSON(decrypted, Map.class);
            String type = (String) content.get("type");
            if (type == null) {
                throw new IllegalArgumentException("Invalid SDAN Negotiation Event: " + e.getId());
            }
            SdanNegotiationEvent event = null;
            switch (type) {
                case "offer":
                    event = new SdanOfferEvent(signer, e, content);
                    break;
                case "payment_request":
                    event = new SdanPaymentRequestEvent(signer, e, offer, content);
                    break;
                case "accept_offer":
                    event = new SdanAcceptOfferEvent(signer, e, offer, content);
                    break;
                case "payout":
                    event = new SdanPayoutEvent(signer, e, offer, content);
                    break;
                case "bail":
                    event = new SdanBailEvent(signer, e, offer, content);
                    break;
                default:
            }
            if(event==null){
                throw new IllegalArgumentException("Unknown SDAN Negotiation Event type: " + type);
            }

            try{
                event.checkValid();
                return (T) event;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
           
    }
}
