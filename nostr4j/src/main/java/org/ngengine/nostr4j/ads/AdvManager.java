package org.ngengine.nostr4j.ads;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.logging.Level;

import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation.AdvListener;
import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation.NotifyPayout;
import org.ngengine.nostr4j.ads.negotiation.SdanBailEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPaymentRequestEvent;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.Wallet;

public class AdvManager implements AdvListener {
    private static record PendingOffer(
        SdanOfferEvent offer,
        SdanNegotiation neg,
        Instant expiration
    ) {
    }

    private static record PendingPayout(
        String invoice
    ) {
    }

    private final SdanClient client;
    private final Number budgetMsats;
    private final ArrayList<PendingOffer> pendingOffers = new ArrayList<>();
    private final Wallet wallet;
    private long spent = 0;

    public AdvManager(
        SdanClient client,
        Wallet wallet,
        Number budgetMsats
    ) {
        this.client = client;
        this.budgetMsats = budgetMsats;            
        this.wallet = wallet;
    }

    protected synchronized void cleanExpired(){
        Iterator<PendingOffer> it = pendingOffers.iterator();
        while(it.hasNext()){
            PendingOffer p = it.next();
            Instant now = Instant.now();
            if (
                (p.offer().getExpiration() != null && p.offer().getExpiration().isBefore(now)) ||
                (p.expiration != null && p.expiration.isBefore(now))                        
            ) {
                SdanAdvSideNegotiation.logger.fine("Removing expired offer: " + p);
                it.remove();
                p.neg.close();
            }
        }
    }

    protected synchronized boolean checkBudget(SdanNegotiation neg) {
        cleanExpired();
        SdanBidEvent bid = neg.getBidding();
        long budgetLeft = budgetMsats.longValue() - spent - pendingOffers.size() * bid.getBidMsats();
        if (budgetLeft < bid.getBidMsats()) {
            SdanAdvSideNegotiation.logger.warning("Not enough budget left to accept offer for " + neg);
            neg.bail( SdanBailEvent.Reason.OUT_OF_BUDGET);
            neg.close();
            client.close(bid);
            return false;
        }
        return true;            
    }

    protected synchronized boolean addPendingOffer(SdanNegotiation neg,SdanOfferEvent offer) {
        if(!checkBudget(neg)) return false;
        pendingOffers.add(
            new PendingOffer(offer,neg, Instant.now().plus(neg.getBidding().getHoldTime()))
        );
        return true;
    }

    private synchronized void registerPaid( SdanNegotiation neg){
        SdanBidEvent bid = neg.getBidding();
        spent += bid.getBidMsats();
        Iterator<PendingOffer> it = pendingOffers.iterator();
        while(it.hasNext()){
            PendingOffer p = it.next();
            if (p.neg.equals(neg)) {
                it.remove();
                SdanAdvSideNegotiation.logger.fine("Removing paid offer: " + p);
                break;  
            }
        }
    }

    @Override
    public synchronized void onBail(SdanNegotiation neg, SdanBailEvent event) {
        SdanAdvSideNegotiation.logger.fine("Bail event received: " + event);
        Iterator<PendingOffer> it = pendingOffers.iterator();
        while (it.hasNext()) {
            PendingOffer p = it.next();
            if (p.neg.equals(neg)) {
                it.remove();
                SdanAdvSideNegotiation.logger.fine("Removing bailed offer: " + p);
                break;
            }
        }
    }

    @Override
    public synchronized void onPaymentRequest(SdanNegotiation neg, SdanPaymentRequestEvent event, String invoice, NotifyPayout notifyPayout) {
        try{
            SdanAdvSideNegotiation.logger.fine("Payment request event received: " + event);
            if(!checkBudget( neg)) return;
            PayResponse res = wallet.payInvoice(invoice, neg.getBidding().getBidMsats()).await();                
            registerPaid( neg);           
            notifyPayout.call(
                "NOSTR-SDAN: Payout for " + neg.getBidding().getAdId()+ " completed!",
                res.preimage()
            );
        } catch (Exception e) {
            SdanAdvSideNegotiation.logger.log(Level.WARNING, "Failed to process payment request: " + e.getMessage(), e);
            neg.bail(SdanBailEvent.Reason.NO_ROUTE);
        }
    }

    @Override
    public synchronized void onOffer(SdanNegotiation neg, SdanOfferEvent offer, Runnable acceptOffer) {
        SdanAdvSideNegotiation.logger.fine("Offer received: " + offer);
        acceptOffer.run();
        SdanAdvSideNegotiation.logger.fine("Offer accepted: " + offer);
     
    }



}