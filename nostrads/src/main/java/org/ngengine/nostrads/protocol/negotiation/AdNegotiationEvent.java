package org.ngengine.nostrads.protocol.negotiation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.AdEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public abstract class AdNegotiationEvent extends AdEvent{
    public static final int KIND = 30101;

    private final AdOfferEvent offer;
    private final static Logger logger = Logger.getLogger(AdNegotiationEvent.class.getName());

    public AdNegotiationEvent(

        SignedNostrEvent event, 
        AdOfferEvent offer,
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

   
    public AdOfferEvent getOffer(){
        return offer;
    }

    @Override
    public void checkValid() throws Exception{
        super.checkValid();
        getType();
        getTargetEvent();
        getCounterparty();        
    }


    public static interface Factory<T extends AdNegotiationEvent> {
        T create(NostrSigner signer, SignedNostrEvent event, AdOfferEvent offer, Map<String, Object> unencryptedContent);
    }
    
    public static abstract class Builder<T extends AdNegotiationEvent> {
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
                if (negotiationTarget instanceof AdOfferEvent) {
                    AdOfferEvent offer = (AdOfferEvent) negotiationTarget;
                    if (pubkey.equals(offer.getPubkey())) {
                        counterparty = offer.getCounterparty();
                    } else {
                        counterparty = offer.getPubkey();
                    }
                } else if(negotiationTarget instanceof AdBidEvent){
                    counterparty = ((AdBidEvent) negotiationTarget).getDelegate();
                } else {
                    throw new IllegalArgumentException("Negotiation target must be an offer or bidding event");
                }


                this.event.withTag("p", counterparty.asHex());

                return signer.encrypt(NGEPlatform.get().toJSON(content), counterparty).compose(encrypted -> {
                    event.withContent(encrypted);
                    return signer.sign(event);
                }).then(signed -> {
                    return this.factory.create(signer, signed, negotiationTarget instanceof AdOfferEvent ? (AdOfferEvent)negotiationTarget : null, content);
                }).then(ev -> {
                    return (T) ev;
                });   
               
            });
        }        

        
    }

    

    @SuppressWarnings("unchecked")
    public static <T extends AdNegotiationEvent> AsyncTask<T> cast(
        NostrSigner signer,
        SignedNostrEvent e,
        AdOfferEvent offer
        
    ) {
        return ( AsyncTask<T> )signer.decrypt(e.getContent(), e.getPubkey()).then(decrypted -> {
            Map<String, Object> content = NGEPlatform.get().fromJSON(decrypted, Map.class);
            String type = (String) content.get("type");
            if (type == null) {
                throw new IllegalArgumentException("Invalid Ad Negotiation Event: " + e.getId());
            }
            AdNegotiationEvent event = null;
            switch (type) {
                case "offer":
                    event = new AdOfferEvent(signer, e, content);
                    break;
                case "payment_request":
                    event = new AdPaymentRequestEvent(signer, e, offer, content);
                    break;
                case "accept_offer":
                    event = new AdAcceptOfferEvent(signer, e, offer, content);
                    break;
                case "payout":
                    event = new AdPayoutEvent(signer, e, offer, content);
                    break;
                case "bail":
                    event = new AdBailEvent(signer, e, offer, content);
                    break;
                default:
            }
            if(event==null){
                throw new IllegalArgumentException("Unknown Ad Negotiation Event type: " + type);
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
