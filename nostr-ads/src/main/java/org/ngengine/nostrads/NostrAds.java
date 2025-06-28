package org.ngengine.nostrads;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.ads.SdanActionType;
import org.ngengine.nostr4j.ads.SdanBidEvent;
import org.ngengine.nostr4j.ads.SdanClient;
import org.ngengine.nostr4j.ads.SdanMimeType;
import org.ngengine.nostr4j.ads.SdanSize;
import org.ngengine.nostr4j.ads.SdanTaxonomy;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.nostrads.AdvListenerWrapper.NotifyCallbackWrapper;
import org.ngengine.nostrads.AdvListenerWrapper.FunctionWrapper;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSFunction;

public class NostrAds {
    private static final Logger logger = Logger.getLogger(NostrAds.class.getName());
    
    private SdanClient client;
    private NostrPool pool;
    private SdanTaxonomy taxonomy;


    @JSExport
    public void close(){
        NostrAdsModule.initPlatform();
        client.close();
        pool.close();        
    }

    @JSExport
    public NostrAds(
        String[] relays, 
        String appKey,
        String adsKey
    ) throws IOException{
        NostrAdsModule.initPlatform();
        pool = new NostrPool();
        for(int i = 0; i < relays.length; i++){
            System.out.println("Connecting to relay: " + relays[i]);
            pool.connectRelay(new NostrRelay(relays[i]));
        }

        System.out.println("Connected to relays: " + pool.getRelays().size());

        NostrPublicKey appKeyN = appKey.startsWith("npub") ? NostrPublicKey.fromBech32(appKey) : NostrPublicKey.fromHex(appKey);
        NostrPrivateKey adsKeyN = adsKey.startsWith("nsec") 
            ? NostrPrivateKey.fromBech32(adsKey) 
            : NostrPrivateKey.fromHex(adsKey);

        System.out.println("App configured");
        
        NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair(adsKeyN));
        
        System.out.println("Signer initialized");
        
        System.out.println("Loading taxonomy...");
        taxonomy = new SdanTaxonomy();
        System.out.println("Taxonomy loaded");
        
        client = new SdanClient(
            pool,
            signer,
            appKeyN,    
            taxonomy
        );

        System.out.println("Nostr Ads is ready!");
    }

 
    @JSExport
    public void publishNewBid(
        BidInputObject bid,
        BidCallback callback
    ) throws Exception{
        NostrAdsModule.initPlatform();

        // Get required string properties
        String description = Objects.requireNonNull(bid.getDescription(), "Description is required");
        String mimeType = Objects.requireNonNull(bid.getMimeType(), "Mime type is required");
        String payload = Objects.requireNonNull(bid.getPayload(), "Payload is required");
        String size = Objects.requireNonNull(bid.getSize(), "Size is required");
        String link = Objects.requireNonNull(bid.getLink(), "Link is required");
        String actionType = Objects.requireNonNull(bid.getActionType(), "Action type is required");

        // Get optional string properties
        String callToAction = bid.getCallToAction();
        String delegate = bid.getDelegate();

        // Get required numeric properties
        long bidMsats = (long) bid.getBidMsats();
        int holdTime = (int) bid.getHoldTime();

        // Get optional numeric property with default
        double expireAtValue = bid.getExpireAt();
        long expireAt = Double.isNaN(expireAtValue) ? -1 : (long) expireAtValue;

        // Get array properties
        String[] categories = bid.getCategories();
        String[] languages = bid.getLanguages();
        String[] offerersWhitelist = bid.getOfferersWhitelist();
        String[] appsWhitelist = bid.getAppsWhitelist();

        // Process categories
        List<SdanTaxonomy.Term> categoriesList = categories != null ? Arrays.stream(categories)
                .map(t -> taxonomy.getByPath(t))
                .toList() : null;

        // Process languages
        List<String> languagesList = languages != null ? Arrays.asList(languages) : null;

        // Process whitelist arrays
        List<NostrPublicKey> offerersWhitelistList = offerersWhitelist != null ? Arrays.stream(offerersWhitelist)
                .map(NostrPublicKey::fromHex)
                .toList() : null;

        List<NostrPublicKey> appsWhitelistList = appsWhitelist != null ? Arrays.stream(appsWhitelist)
                .map(NostrPublicKey::fromHex)
                .toList() : null;

        // Convert enums
        SdanMimeType mimeTypeEnum = SdanMimeType.fromString(mimeType);
        SdanSize sizeEnum = SdanSize.fromString(size);
        SdanActionType actionTypeEnum = SdanActionType.fromValue(actionType);

        // Create objects
        Duration holdTimeDuration = Duration.ofSeconds(holdTime);
        NostrPublicKey delegatePublicKey = delegate != null ? NostrPublicKey.fromHex(delegate) : null;
        Instant expireAtInstant = expireAt > 0 ? Instant.ofEpochMilli(expireAt) : null;

        // Call client
        client.newBid(
                description,
                categoriesList,
                languagesList,
                offerersWhitelistList,
                appsWhitelistList,
                mimeTypeEnum,
                payload,
                sizeEnum,
                link,
                callToAction,
                actionTypeEnum,
                bidMsats,
                holdTimeDuration,
                delegatePublicKey,
                expireAtInstant)
        .then(bidEvent->{
            client.publishBid(bidEvent);
            callback.onBid(TeaVMJsConverter.toJSObject(bidEvent.toMap()), null);
            return null;
        }).catchException(err->{
            logger.log(Level.SEVERE, "Error publishing bid", err);
            callback.onBid(null, err.getMessage());
        });

        

    
    }
  
    @JSExport
    public Closer handleBid(
       JSObject bidEvent,
       JSObject listeners
    ) {
        NostrAdsModule.initPlatform();
        Map map = TeaVMJsConverter.toJavaMap(bidEvent);
        Map listenersMap = TeaVMJsConverter.toJavaMap(listeners);
        SignedNostrEvent event = new SignedNostrEvent(map);
        SdanBidEvent bid = new SdanBidEvent(taxonomy, event);
        if (bid.isValid()) {
            JSFunction bailCallback = (JSFunction) listenersMap.get("onBail");
            JSFunction offerCallback = (JSFunction) listenersMap.get("offerFilter");
            JSFunction payCallback = (JSFunction) listenersMap.get("pay");
            logger.log(Level.INFO, "Handling bid: " + bid.getId());
            logger.log(Level.INFO, "onBail: " + bailCallback);
            logger.log(Level.INFO, "offerFilter: " + offerCallback);
            logger.log(Level.INFO, "pay: " + payCallback);

            if(payCallback == null){
                throw new IllegalArgumentException("Pay callback is required");
            }

            AdvListenerWrapper advListener = new AdvListenerWrapper((JSObject bidArg, JSObject eventArg, String invoice, FunctionWrapper punishFun, NotifyCallbackWrapper notifyPayoutFun)->{
                payCallback.call(null,  bidArg, eventArg, invoice, punishFun, notifyPayoutFun);
            });

            if(bailCallback != null){
                advListener.setBailCallback((bidArg, eventArg, punishFun)->{
                    bailCallback.call(null, bidArg, eventArg, punishFun);
                });
            }

            if(offerCallback != null){
                advListener.setOfferCallback((bidArg, eventArg, punishFun, acceptOffer)->{
                    offerCallback.call(null, bidArg, eventArg, punishFun, acceptOffer);
                });
            }

            NostrSubscription sub = client.handleBid(bid, advListener);
            return () -> {
                System.out.println("Closing subscription for bid: " + bid.getId());
                sub.close();
            };
        } else {
            throw new IllegalArgumentException("Invalid bid event: " + event.getId());
        }
    }


    @JSExport
    public Closer handleOffers(
        JSArray filters,
        JSObject listeners

    ){
        NostrAdsModule.initPlatform();
        ArrayList<NostrFilter> filtersList = new ArrayList<>();
        for (int i = 0; i < filters.getLength(); i++) {
            JSObject filterObj = (JSObject) filters.get(i);
            Map map = TeaVMJsConverter.toJavaMap(filterObj);
            NostrFilter filter = new NostrFilter(map);
            filtersList.add(filter);
        }
        Map listenersMap = TeaVMJsConverter.toJavaMap(listeners);

        JSFunction showCallback = (JSFunction) listenersMap.get("show");
        JSFunction bidFilter = (JSFunction) listenersMap.get("bidFilter");
        JSFunction beforePayment = (JSFunction) listenersMap.get("beforePayment");
        JSFunction verifyPayment = (JSFunction) listenersMap.get("verifyPayment");
        JSFunction bail = (JSFunction) listenersMap.get("bail");
        logger.log(Level.INFO, "Handling offers with filters: " + filtersList);
        logger.log(Level.INFO, "show: " + showCallback);
        logger.log(Level.INFO, "bidFilter: " + bidFilter);
        logger.log(Level.INFO, "beforePayment: " + beforePayment);
        logger.log(Level.INFO, "verifyPayment: " + verifyPayment);
        logger.log(Level.INFO, "bail: " + bail);

        if(showCallback==null){
            throw new IllegalArgumentException("Show callback is required");
        }

        OffererListenerWrapper listener = new OffererListenerWrapper(( bid, notifyShown) -> {
            showCallback.call(null, bid, notifyShown);           
        });

        if(bidFilter != null){
            listener.setBidFilter((bid, makeOffer) -> {
                bidFilter.call(null, bid, makeOffer);
            });
        }

        if(beforePayment != null){
            listener.setPaymentRequestCallback((bid) -> {
                beforePayment.call(null, bid);
            });
        }

        if(verifyPayment != null){
            listener.setVerifyCallback((bid, preimage, punishFun) -> {
                verifyPayment.call(null, bid, preimage, punishFun);
            });

        }

        if(bail != null){
            listener.setBailCallback((bid, event, punishFun) -> {
                bail.call(null, bid, event, punishFun);
            });

        }
        
        NostrSubscription sub = client.listenForBids(filtersList, listener);
        return () -> {
            System.out.println("Closing subscription for offers");
            sub.close();
        };
         
    }
}
