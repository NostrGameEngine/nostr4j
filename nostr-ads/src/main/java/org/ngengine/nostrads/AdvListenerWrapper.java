package org.ngengine.nostrads;

import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.ads.AdvManager;
import org.ngengine.nostr4j.ads.SdanClient;
import org.ngengine.nostr4j.ads.SdanNegotiation;
import org.ngengine.nostr4j.ads.negotiation.SdanBailEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.ngengine.wallets.Wallet;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public class AdvListenerWrapper extends AdvManager {
    private static final Logger logger = Logger.getLogger(AdvListenerWrapper.class.getName());

    @JSFunctor
    public static interface FunctionWrapper {
        void call();
    }   

  
    @JSFunctor
    public static interface BailCallbackWrapper {
        void call(JSObject bid, JSObject event, FunctionWrapper punish);
    }

    @JSFunctor
    public static interface OfferCallbackWrapper {
        void call(JSObject bid, JSObject event, FunctionWrapper punish, FunctionWrapper acceptOffer);
    }


    public static interface BudgetUpdateCallbackWrapper {
        void call(long budgetMsats, long spentMsats);
    }

    private BailCallbackWrapper bailCallback;
    private OfferCallbackWrapper offerCallback;
 
    public AdvListenerWrapper(
        SdanClient client,
        Wallet wallet,
        Number budgetMsats
    ) throws URISyntaxException {
       super(client, wallet, budgetMsats);        
    }


    public void setBailCallback(BailCallbackWrapper bailListener) {
        this.bailCallback = bailListener;
    }

    public void setOfferCallback(OfferCallbackWrapper listener) {
        this.offerCallback = listener;
    }

    @Override
    public void onBail(SdanNegotiation neg, SdanBailEvent event) {
        super.onBail(neg, event);
        logger.fine("Bail event received: " + event);
        if(bailCallback != null) {
            JSObject bid = TeaVMJsConverter.toJSObject(neg.getBidding().toMap());
            JSObject ev = TeaVMJsConverter.toJSObject(event.toMap());            
            bailCallback.call(bid, ev, ()->{
                logger.fine("Punishing counterparty for bail: " + event);
                try {
                    neg.punishCounterparty();
                } catch (Exception e) {
                     logger.log(Level.WARNING, "Failed to punish counterparty", e);
                }
            });
        }
    }

    @Override
    public void onOffer(SdanNegotiation neg, SdanOfferEvent nev, Runnable acceptOffer) {
        logger.fine("Offer event received: " + nev);       
        if(offerCallback != null) {
            JSObject bid = TeaVMJsConverter.toJSObject(neg.getBidding().toMap());
            JSObject event = TeaVMJsConverter.toJSObject(nev.toMap());            
            offerCallback.call(bid, event, ()->{
                logger.fine("Punishing counterparty for offer: " + nev);
                try {
                    neg.punishCounterparty();
                } catch (Exception e) {
                     logger.log(Level.WARNING, "Failed to punish counterparty", e);
                }
            }, ()->{
                if (!addPendingOffer(neg, nev)) {
                    logger.fine("Offer rejected");
                    return;
                }
                logger.fine("Accepting offer: " + nev);
                acceptOffer.run();
            });
        } else {
            if (!addPendingOffer(neg, nev)) {
                logger.fine("Offer rejected");
                return;
            }
            acceptOffer.run();
        }
    }
}
