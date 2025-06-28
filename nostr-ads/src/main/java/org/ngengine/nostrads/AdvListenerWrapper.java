package org.ngengine.nostrads;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation;
import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation.NotifyPayout;
import org.ngengine.nostr4j.ads.SdanNegotiation;
import org.ngengine.nostr4j.ads.negotiation.SdanBailEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPaymentRequestEvent;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public class AdvListenerWrapper implements SdanAdvSideNegotiation.AdvListener {
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

    @JSFunctor
    public static interface NotifyCallbackWrapper {
        void call(String message, String preimage);
    }

    @JSFunctor
    public static interface PaymentRequestCallbackWrapper {
        void call(JSObject bid, JSObject event, String invoice, FunctionWrapper punish, NotifyCallbackWrapper notifyPayout);
    }

    private BailCallbackWrapper bailCallback;
    private OfferCallbackWrapper offerCallback;
    private final PaymentRequestCallbackWrapper payRequestCallback;

    public AdvListenerWrapper(PaymentRequestCallbackWrapper paymentRequestListener) {
        this.payRequestCallback = Objects.requireNonNull(paymentRequestListener, "PaymentRequestCallbackWrapper cannot be null");
    }

    public void setBailCallback(BailCallbackWrapper bailListener) {
        this.bailCallback = bailListener;
    }

    public void setOfferCallback(OfferCallbackWrapper listener) {
        this.offerCallback = listener;
    }

    @Override
    public void onBail(SdanNegotiation neg, SdanBailEvent event) {
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
                logger.fine("Accepting offer: " + nev);
                acceptOffer.run();
            });
        } else {
            acceptOffer.run();
        }
    }

    @Override
    public void onPaymentRequest(SdanNegotiation neg, SdanPaymentRequestEvent event, String invoice, NotifyPayout notifyPayout) {
        logger.fine("Payment request event received: " + event);
        if(payRequestCallback != null) {
            JSObject bid = TeaVMJsConverter.toJSObject(neg.getBidding().toMap());
            JSObject ev = TeaVMJsConverter.toJSObject(event.toMap());
            
            payRequestCallback.call(bid, ev, invoice, ()->{
                logger.fine("Punishing counterparty for payment request: " + event);
                try {
                    neg.punishCounterparty();
                } catch (Exception e) {
                     logger.log(Level.WARNING, "Failed to punish counterparty", e);
                }
            }, (message, preimage) -> {
                logger.fine("Notifying payout: " + message + ", preimage: " + preimage);
                notifyPayout.call(message, preimage);
            });
        }

         
    }
    
}
