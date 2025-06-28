package org.ngengine.nostrads;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation;
import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation.NotifyPayout;
import org.ngengine.nostr4j.ads.SdanNegotiation;
import org.ngengine.nostr4j.ads.negotiation.SdanAcceptOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanBailEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPaymentRequestEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPayoutEvent;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.ngengine.nostr4j.ads.SdanOffererSideNegotiation;
public class OffererListenerWrapper implements SdanOffererSideNegotiation.OfferListener {
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
    public static interface VerifyPaymentCallbackWrapper {
        void call(JSObject bid,  String preimage, FunctionWrapper punish);
    }

    @JSFunctor
    public static interface PaymentRequestCallbackWrapper {
        void call(JSObject bid);
    }


    @JSFunctor
    public static interface ShowAdCallbackWrapper {
        void call(JSObject bid,  FunctionWrapper notifyShown);
    }

    @JSFunctor
    public static interface BidFilterWrapper {
        void call(JSObject bid, FunctionWrapper makeOffer);
    }

    private BailCallbackWrapper bailCallback;
    private VerifyPaymentCallbackWrapper verifyCallback;
    private PaymentRequestCallbackWrapper paymentRequestCallback;
    private final ShowAdCallbackWrapper showAdCallback;
    private BidFilterWrapper bidFilter;
    private volatile boolean requestedPayment = false;

    public OffererListenerWrapper(ShowAdCallbackWrapper showAdCallback) {
        this.showAdCallback =  Objects.requireNonNull(showAdCallback, "ShowAdCallbackWrapper cannot be null");
    }

    public void setBailCallback(BailCallbackWrapper bailListener) {
        this.bailCallback = bailListener;
    }

    public void setVerifyCallback(VerifyPaymentCallbackWrapper verifyCallback) {
        this.verifyCallback = verifyCallback;
    }
    public void setPaymentRequestCallback(PaymentRequestCallbackWrapper paymentRequestCallback) {
        this.paymentRequestCallback = paymentRequestCallback;
    }
    public void setBidFilter(BidFilterWrapper bidFilter) {
        this.bidFilter = bidFilter;
    }
    public void setRequestedPayment(boolean requestedPayment) {
        this.requestedPayment = requestedPayment;
    }
    



    @Override
    public void onBail(SdanNegotiation neg, SdanBailEvent event) {
        
        logger.fine("Bail event received: " + event);


        // if bailed after requesting payment, we publish automatically
        if (requestedPayment) {
            logger.fine("Bail after payment request, punishing counterparty directly");
            try {
                neg.punishCounterparty();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to punish counterparty", e);
            }
        }
        //


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
    public void verifyPayout(SdanNegotiation neg, SdanPayoutEvent event) {
         if(verifyCallback!=null){
             String preimage = event.getPreimage();
             JSObject bid = TeaVMJsConverter.toJSObject(neg.getBidding().toMap());
             verifyCallback.call(bid,  preimage, () -> {
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
    public void onRequestingPayment(SdanNegotiation neg) {
        requestedPayment = true;
        if(paymentRequestCallback!=null){
            JSObject bid = TeaVMJsConverter.toJSObject(neg.getBidding().toMap());
            paymentRequestCallback.call(bid);
        }
       
    }

    @Override
    public void showAd(SdanNegotiation neg, SdanAcceptOfferEvent acp, Consumer<String> notifyShown) {
        JSObject bid = TeaVMJsConverter.toJSObject(neg.getBidding().toMap());
        showAdCallback.call(bid,  () -> {
            logger.fine("Ad was shown for bidding: " + neg.getBidding().getId() + " ... notifying shown");
            notifyShown.accept(acp.getMessage());
        });
    }

    @Override
    public void onBid(SdanOffererSideNegotiation neg, Runnable makeOffer) {
        if(bidFilter==null){
            makeOffer.run();
        } else{
            bidFilter.call(TeaVMJsConverter.toJSObject(neg.getBidding().toMap()), ()-> {
                logger.fine("Making offer for bidding: " + neg.getBidding().getId());
                makeOffer.run();
            });
        }
        
    }
    
}
