package org.ngengine.nostrads;

 import java.io.IOException;
import java.time.Duration;
 import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
 import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.ads.SdanActionType;
import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation;
import org.ngengine.nostr4j.ads.SdanBidEvent;
import org.ngengine.nostr4j.ads.SdanClient;
import org.ngengine.nostr4j.ads.SdanMimeType;
import org.ngengine.nostr4j.ads.SdanNegotiation;
import org.ngengine.nostr4j.ads.SdanOffererSideNegotiation;
import org.ngengine.nostr4j.ads.SdanSize;
import org.ngengine.nostr4j.ads.SdanTaxonomy;
import org.ngengine.nostr4j.ads.negotiation.SdanAcceptOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanBailEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPaymentRequestEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPayoutEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.teavm.TeaVMJsConverter;
import org.ngengine.platform.teavm.TeaVMPlatform;
import org.teavm.jso.JSClass;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSExportClasses;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSFunction;


 
@JSExportClasses({
    NostrAds.class,
})
public class NostrAdsModule {
    private static boolean platformInitialized = false;

    public static void initPlatform() {
        if (!platformInitialized) {
            NGEPlatform.set(new TeaVMPlatform());
            platformInitialized = true;
        }
    }
    @JSExport
    public static String newAdsKey() {
        initPlatform();
        return NostrPrivateKey.generate().asHex();
    }

    // private static SdanClient client;
    // private static NostrPool pool;
    // private static SdanTaxonomy taxonomy;
    // private static boolean platformInitialized = false;

    // private static void initPlatform() {
    //     if (!platformInitialized) {
    //         NGEPlatform.set(new TeaVMPlatform());
    //         platformInitialized = true;
    //     }
    // }


    // @JSExport
    // public static void close(){
    //     initPlatform();
    //     client.close();
    //     pool.close();        
    // }

    // @JSExport
    // public static void start(
    //     String[] relays, 
    //     String appKey,
    //     String adsKey
    // ) throws IOException{
    //     initPlatform();
    //     pool = new NostrPool();
    //     for(int i = 0; i < relays.length; i++){
    //         System.out.println("Connecting to relay: " + relays[i]);
    //         pool.connectRelay(new NostrRelay(relays[i]));
    //     }

    //     System.out.println("Connected to relays: " + pool.getRelays().size());

    //     NostrPublicKey appKeyN = appKey.startsWith("npub") ? NostrPublicKey.fromBech32(appKey) : NostrPublicKey.fromHex(appKey);
    //     NostrPrivateKey adsKeyN = adsKey.startsWith("nsec") 
    //         ? NostrPrivateKey.fromBech32(adsKey) 
    //         : NostrPrivateKey.fromHex(adsKey);

    //     System.out.println("App configured");
        
    //     NostrSigner signer = new NostrKeyPairSigner(new NostrKeyPair(adsKeyN));
        
    //     System.out.println("Signer initialized");
        
    //     System.out.println("Loading taxonomy...");
    //     taxonomy = new SdanTaxonomy();
    //     System.out.println("Taxonomy loaded");
        
    //     client = new SdanClient(
    //         pool,
    //         signer,
    //         appKeyN,    
    //         taxonomy
    //     );

    //     System.out.println("Nostr Ads is ready!");
    // }

 
    // @JSExport
    // public static JSObject publishNewBid(
    //     BidInputObject bid
    // ) throws Exception{
    //     initPlatform();

    //     // Get required string properties
    //     String description = Objects.requireNonNull(bid.getDescription(), "Description is required");
    //     String mimeType = Objects.requireNonNull(bid.getMimeType(), "Mime type is required");
    //     String payload = Objects.requireNonNull(bid.getPayload(), "Payload is required");
    //     String size = Objects.requireNonNull(bid.getSize(), "Size is required");
    //     String link = Objects.requireNonNull(bid.getLink(), "Link is required");
    //     String actionType = Objects.requireNonNull(bid.getActionType(), "Action type is required");

    //     // Get optional string properties
    //     String callToAction = bid.getCallToAction();
    //     String delegate = bid.getDelegate();

    //     // Get required numeric properties
    //     long bidMsats = (long) bid.getBidMsats();
    //     int holdTime = (int) bid.getHoldTime();

    //     // Get optional numeric property with default
    //     double expireAtValue = bid.getExpireAt();
    //     long expireAt = Double.isNaN(expireAtValue) ? -1 : (long) expireAtValue;

    //     // Get array properties
    //     String[] categories = bid.getCategories();
    //     String[] languages = bid.getLanguages();
    //     String[] offerersWhitelist = bid.getOfferersWhitelist();
    //     String[] appsWhitelist = bid.getAppsWhitelist();

    //     // Process categories
    //     List<SdanTaxonomy.Term> categoriesList = categories != null ? Arrays.stream(categories)
    //             .map(t -> taxonomy.getByPath(t))
    //             .toList() : null;

    //     // Process languages
    //     List<String> languagesList = languages != null ? Arrays.asList(languages) : null;

    //     // Process whitelist arrays
    //     List<NostrPublicKey> offerersWhitelistList = offerersWhitelist != null ? Arrays.stream(offerersWhitelist)
    //             .map(NostrPublicKey::fromHex)
    //             .toList() : null;

    //     List<NostrPublicKey> appsWhitelistList = appsWhitelist != null ? Arrays.stream(appsWhitelist)
    //             .map(NostrPublicKey::fromHex)
    //             .toList() : null;

    //     // Convert enums
    //     SdanMimeType mimeTypeEnum = SdanMimeType.fromString(mimeType);
    //     SdanSize sizeEnum = SdanSize.fromString(size);
    //     SdanActionType actionTypeEnum = SdanActionType.fromValue(actionType);

    //     // Create objects
    //     Duration holdTimeDuration = Duration.ofSeconds(holdTime);
    //     NostrPublicKey delegatePublicKey = delegate != null ? NostrPublicKey.fromHex(delegate) : null;
    //     Instant expireAtInstant = expireAt > 0 ? Instant.ofEpochMilli(expireAt) : null;

    //     // Call client
    //     SdanBidEvent bidEvent =  client.newBid(
    //             description,
    //             categoriesList,
    //             languagesList,
    //             offerersWhitelistList,
    //             appsWhitelistList,
    //             mimeTypeEnum,
    //             payload,
    //             sizeEnum,
    //             link,
    //             callToAction,
    //             actionTypeEnum,
    //             bidMsats,
    //             holdTimeDuration,
    //             delegatePublicKey,
    //             expireAtInstant).await();

    //     client.publishBid(bidEvent);

    //    return TeaVMJsConverter.toJSObject(bidEvent.toMap());
    // }
  
    // @JSExport
    // public static String newAdsKey(){
    //     initPlatform();
    //     return NostrPrivateKey.generate().asHex();
    // }

    // @JSExport
    // public static Closer handleBid(
    //    JSObject bidEvent
    // ) {
    //     initPlatform();
    //     Map map = TeaVMJsConverter.toJavaMap(bidEvent);
    //     SignedNostrEvent event = new SignedNostrEvent(map);
    //     SdanBidEvent bid = new SdanBidEvent(taxonomy, event);
    //     if (bid.isValid()) {
    //         NostrSubscription sub = client.handleBid(bid, new SdanAdvSideNegotiation.AdvListener(){

    //             @Override
    //             public void onBail(SdanNegotiation neg, SdanBailEvent event) {
    //                 System.out.println("Bail event received: " + event.getId());
    //             }

    //             @Override
    //             public boolean onOffer(SdanNegotiation neg, SdanOfferEvent offer, Runnable acceptOffer) {
    //                 System.out.println("Offer event received: " + offer.getId());
    //                 // Here you can implement logic to accept or reject the offer
    //                 // For now, we will just accept it
    //                 acceptOffer.run();
    //                 return true; // Indicate that the offer was handled
    //             }

    //             @Override
    //             public boolean onPaymentRequest(SdanPaymentRequestEvent event,
    //                     BiConsumer<String, String> notifyPayout) {
    //                System.out.println("Payment request event received: " + event.getId());
    //                 // Here you can implement logic to handle the payment request
    //                 // For now, we will just notify the payout
                    
    //                 return true; // Indicate that the payment request was handled
    //             }

    //         });
    //         return () -> {
    //             System.out.println("Closing subscription for bid: " + bid.getId());
    //             sub.close();
    //         };
    //     } else {
    //         throw new IllegalArgumentException("Invalid bid event: " + event.getId());
    //     }
    // }


    // @JSExport
    // public static Closer handleOffers(
    //     JSArray filters
    // ){
    //     ArrayList<NostrFilter> filtersList = new ArrayList<>();
    //     for (int i = 0; i < filters.getLength(); i++) {
    //         JSObject filterObj = (JSObject) filters.get(i);
    //         Map map = TeaVMJsConverter.toJavaMap(filterObj);
    //         NostrFilter filter = new NostrFilter(map);
    //         filtersList.add(filter);
    //     }
        
    //      NostrSubscription sub = client.listenForBids(filtersList,
    //         new SdanOffererSideNegotiation.OfferListener() {

    //             @Override
    //             public void onBail(SdanNegotiation neg, SdanBailEvent event) {
    //                 System.out.println("[offerer] Bailed by advertiser: " + event);

    //             }

    //             @Override
    //             public void verifyPayout(SdanNegotiation neg, SdanPayoutEvent event) {
    //                 System.out.println("[offerer] Payout verified: " + event);

    //             }

    //             @Override
    //             public void onRequestingPayment(SdanNegotiation neg) {
    //                 System.out.println("[offerer] Requesting payment for negotiation: " + neg);

    //             }

    //             @Override
    //             public void showAd(SdanNegotiation neg, SdanAcceptOfferEvent acp, Consumer<String> notifyShown) {
    //                 System.out.println("[offerer] Showing ad for negotiation: " + neg);
    //                 notifyShown.accept("Ad shown successfully");
    //             }

    //             @Override
    //             public void onBid(SdanOffererSideNegotiation neg,  Runnable makeOffer) {
    //                 System.out.println("[offerer] Received bid: " + neg);
    //                 makeOffer.run(); // automatically make an offer for testing
    //             }

    //         });
    //     return () -> {
    //         System.out.println("Closing subscription for offers");
    //         sub.close();
    //     };
         
    // }


    // @JSFunctor
    // public interface Closer extends JSObject {
    //     void close();
    // }
}
