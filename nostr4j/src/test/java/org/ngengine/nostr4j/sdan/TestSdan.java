package org.ngengine.nostr4j.sdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Test;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.ads.AdvManager;
import org.ngengine.nostr4j.ads.SdanActionType;
import org.ngengine.nostr4j.ads.SdanMimeType;
import org.ngengine.nostr4j.ads.SdanSize;
import org.ngengine.nostr4j.ads.SdanTaxonomy;
import org.ngengine.nostr4j.ads.negotiation.SdanAcceptOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanBailEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanOfferEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPaymentRequestEvent;
import org.ngengine.nostr4j.ads.negotiation.SdanPayoutEvent;
import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation;
import org.ngengine.nostr4j.ads.SdanAdvSideNegotiation.NotifyPayout;
import org.ngengine.nostr4j.ads.SdanBidEvent;
import org.ngengine.nostr4j.ads.SdanBidFilter;
import org.ngengine.nostr4j.ads.SdanClient;
import org.ngengine.nostr4j.ads.SdanNegotiation;
import org.ngengine.nostr4j.ads.SdanOffererSideNegotiation;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;

public class TestSdan {
    private String appkey ="npub13rugy09zg5pssxtjfvgkhjjzkx8swpvwr7e2gymnr2jp2ltveeqs88pjk4";
 
    private static final Logger logger = TestLogger.getRoot(Level.FINEST);
    NostrPool pool;

    // run before all tests
    public TestSdan() {
        pool = new NostrPool();
        pool.connectRelay(new NostrRelay("wss://relay.ngengine.org"));
    }

    // run after all tests
    public void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    public void testSdanBid() throws Exception{
      

        NostrPublicKey appKey = NostrPublicKey.fromBech32(appkey);
        NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);



        SdanClient client = new SdanClient(
            pool,
            advertiserSigner,
            appKey
        );

        SdanTaxonomy taxonomy = client.getTaxonomy();

        SdanBidEvent bid = client.newBid(
            "Test Bid"+Math.random()+Instant.now().toEpochMilli(),
            List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
            null,
            null,
            null,
            SdanMimeType.TEXT_PLAIN,
            "This is a test bid",
            SdanSize.HORIZONTAL_320x50,
            "https://ngengine.org",
            "Click here!",
            SdanActionType.VIEW,
            1000,
            Duration.ofSeconds(60*5),
            null,
            Instant.now().plusSeconds(60*5)
        ).await();
            
        // verify event
        Map<String, Object> data = bid.toMap();
        Map<String,Object> content = NGEPlatform.get().fromJSON((String)data.get("content"), Map.class);
        assertEquals("This is a test bid", content.get("payload"));
        assertEquals("Click here!", content.get("call_to_action"));
        assertEquals("https://ngengine.org", content.get("link"));
        assertTrue(((String) content.get("description")).startsWith("Test Bid")); 
        assertEquals(300L, ((Number) content.get("hold_time")).longValue()); 
        assertEquals(1000L, ((Number) content.get("bid")).longValue());

        List<List<String>> tags = (List<List<String>>) data.get("tags");
        assertNotNull(tags);

        String dTagValue = null;
        String expirationTagValue = null;
        for (List<String> tag : tags) {
            if (tag.get(0).equals("d")) {
                dTagValue = tag.get(1);
            } else if (tag.get(0).equals("expiration")) {
                expirationTagValue = tag.get(1);
            }
        }
        assertNotNull(dTagValue);
        assertTrue(dTagValue.startsWith("nostr4j1j"));
        assertEquals("639", tags.get(1).get(1));
        assertEquals("text/plain", tags.get(2).get(1));
        assertEquals("320x50", tags.get(3).get(1));
        assertEquals("view", tags.get(4).get(1));
        assertEquals("6.4:1", tags.get(5).get(1));
        assertEquals("BTC1_000", tags.get(6).get(1));
        assertNotNull(expirationTagValue);
        assertTrue(expirationTagValue.matches("\\d+"));
        assertNotNull(data.get("created_at")); 



    }
    
    @Test
    public void testTargetedBids() throws Exception{

        NostrPublicKey appKey = NostrPublicKey.fromBech32(appkey);

        {
            NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
            NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);

            SdanClient advClient = new SdanClient(
                pool,
                advertiserSigner,
                appKey
            );

            SdanTaxonomy taxonomy = advClient.getTaxonomy();

            SdanBidEvent bid = advClient.newBid(
                "Test Bid"+Math.random()+Instant.now().toEpochMilli(),
                List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                null,
                null,
                List.of(appKey),
                SdanMimeType.TEXT_PLAIN,
                "This is a test bid",
                SdanSize.HORIZONTAL_320x50,
                "https://ngengine.org",
                "Click here!",
                SdanActionType.VIEW,
                1000,
                Duration.ofSeconds(60*5),
                null,
                Instant.now().plusSeconds(60*5)
            ).await();
            System.out.println("Publishing bid: " + bid);

            advClient.publishBid(bid).await();
        }

        {
            NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
            NostrKeyPairSigner offererSigner = new NostrKeyPairSigner(offererKeyPair);

            SdanClient offererClient = new SdanClient(pool, offererSigner,appKey);


            // no bid
            {
                SdanBidFilter filter = new SdanBidFilter()
                        .onlyForApp( appKey)
                        .withSize(SdanSize.HORIZONTAL_468x60);
                ResultWrapper res = fetchBid(offererClient, filter);
                Object[] bidData = res.get("bid");
                assertNull(bidData);
                
            }

            // find bid
            {
                SdanBidFilter filter = new SdanBidFilter()
                        .onlyForApp( appKey)
                        .withSize(SdanSize.HORIZONTAL_320x50);
                System.out.println("Fetching bid with filter: " + filter);
                ResultWrapper res = fetchBid(offererClient, filter);
                Object[] bidData = res.get("bid");
                assertNotNull(bidData);
                assertTrue(bidData.length == 2);
                SdanOffererSideNegotiation neg = (SdanOffererSideNegotiation) bidData[0];
                neg.close();
                
            }

        }

    }


    @Test
    public void testUntargetedBids() throws Exception {
        NostrPublicKey appKey = NostrPublicKey.fromBech32(appkey);

        {
            NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
            NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);

            SdanClient advClient = new SdanClient(
                    pool,
                    advertiserSigner,
                    appKey
                    );

            SdanTaxonomy taxonomy = advClient.getTaxonomy();

            SdanBidEvent bid = advClient.newBid(
                    "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                    List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                    null,
                    null,
                    null,
                    SdanMimeType.TEXT_PLAIN,
                    "This is a test bid",
                    SdanSize.HORIZONTAL_320x50,
                    "https://ngengine.org",
                    "Click here!",
                    SdanActionType.VIEW,
                    1000,
                    Duration.ofSeconds(60 * 5),
                    null,
                    Instant.now().plusSeconds(60 * 5)).await();
            System.out.println("Publishing bid: " + bid);

            advClient.publishBid(bid).await();
        }

        {
            NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
            NostrKeyPairSigner offererSigner = new NostrKeyPairSigner(offererKeyPair);

            SdanClient offererClient = new SdanClient(pool,offererSigner, appKey);
      

            // find bid
            {
                SdanBidFilter filter = new SdanBidFilter()
                        .withSize(SdanSize.HORIZONTAL_320x50);
                System.out.println("Fetching bid with filter: " + filter);
                ResultWrapper res = fetchBid(offererClient, filter);
                Object[] bidData = res.get("bid");
                assertNotNull(bidData);
                assertTrue(bidData.length == 2);
                SdanOffererSideNegotiation neg = (SdanOffererSideNegotiation) bidData[0];
                neg.close();

            }

        }

    }


    @Test
    public void testCategoryBids() throws Exception {

        NostrPublicKey appKey = NostrPublicKey.fromBech32(appkey);

        {
            NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
            NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);

            SdanClient advClient = new SdanClient(
                    pool,
                    advertiserSigner,
                    appKey);

            SdanTaxonomy taxonomy = advClient.getTaxonomy();

            SdanBidEvent bid = advClient.newBid(
                    "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                    List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                    null,
                    null,
                    null,
                    SdanMimeType.TEXT_PLAIN,
                    "This is a test bid",
                    SdanSize.HORIZONTAL_320x50,
                    "https://ngengine.org",
                    "Click here!",
                    SdanActionType.VIEW,
                    1000,
                    Duration.ofSeconds(60 * 5),
                    null,
                    Instant.now().plusSeconds(60 * 5)).await();

            System.out.println("Publishing bid: " + bid);
            advClient.publishBid(bid).await();
        }

        {
            NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
            NostrKeyPairSigner offererSigner = new NostrKeyPairSigner(offererKeyPair);

            SdanClient offererClient = new SdanClient(pool, offererSigner,appKey);
            
            SdanTaxonomy taxonomy = offererClient.getTaxonomy();

            // find bid
            {
                SdanBidFilter filter = new SdanBidFilter()
                .withCategory(taxonomy.getByPath(
                                "Technology & Computing/Virtual Reality"))
                        .withSize(SdanSize.HORIZONTAL_320x50);
                System.out.println("Fetching bid with filter: " + filter);
                ResultWrapper res = fetchBid(offererClient, filter);
                Object[] bidData = res.get("bid");
                assertNotNull(bidData);
                assertTrue(bidData.length == 2);
                SdanOffererSideNegotiation neg = (SdanOffererSideNegotiation) bidData[0];
                neg.close();

            }

        }

    }

    
    @Test
    public void testFlow() throws Exception {

        String nwc = "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=520eaf4b4e7fcc0bb1498829955179e0693eeed59a2453807096e6bfd81cfb12";
        NWCWallet wallet = new NWCWallet(new NWCUri(nwc));
        
        NostrPublicKey appKey = NostrPublicKey.fromBech32(appkey);

        
        NostrKeyPair advertiserKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner advertiserSigner = new NostrKeyPairSigner(advertiserKeyPair);

        SdanClient advClient = new SdanClient(
                pool,
                advertiserSigner,
                appKey
                );

        SdanTaxonomy taxonomy = advClient.getTaxonomy();
        SdanBidEvent bid = advClient.newBid(
                "Test Bid" + Math.random() + Instant.now().toEpochMilli(),
                List.of(taxonomy.getByPath("Technology & Computing/Virtual Reality")),
                null,
                null,
                null,
                SdanMimeType.TEXT_PLAIN,
                "This is a test bid",
                SdanSize.HORIZONTAL_320x50,
                "https://ngengine.org",
                "Click here!",
                SdanActionType.VIEW,
                1000,
                Duration.ofSeconds(60 * 5),
                null,
                Instant.now().plusSeconds(60 * 5)).await();

        System.out.println("Publishing bid: " + bid);
        advClient.publishBid(bid).await();
        
        NostrSubscription sub = advClient.handleBid(bid, new AdvManager(
            advClient,
            wallet,
            1010
        ));

    
        
        NostrKeyPair offererKeyPair = new NostrKeyPair(NostrPrivateKey.generate());
        NostrKeyPairSigner offererSigner = new NostrKeyPairSigner(offererKeyPair);

        SdanClient offererClient = new SdanClient(pool, offererSigner, appKey);

          
        NostrFilter filter = new SdanBidFilter().withId(bid.getId());
        ResultWrapper res = fetchBid(offererClient, filter);
        Object[] bidData = res.get("bid");
        System.out.println("Found bid: " + (bidData != null ? bidData[0] : "null"));
        assertNotNull(bidData);
        assertTrue(bidData.length == 2);
         
        

        Object[] payout = res.get("payout");
        assertNotNull(payout); // no payout yet
        System.out.println("Payout: " + payout[1]);


        sub.close(); // close the subscription
    }

    public  static class ResultWrapper {
        private Map<String,Object[]> bids = new ConcurrentHashMap<>( );
        public Runnable close;
        
        
        public Object[] get(String key) {
            Instant timeout = Instant.now().plusSeconds(14);
            Object[] ref = null;
            do {
                 ref = bids.get(key);
            
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for " + key, e);
                }
            } while( ref == null && Instant.now().isBefore(timeout) );
             return ref;
        }

        public void set(String key, Object... data) {
            if(data == null || data.length == 0) {
                bids.remove(key);
            } else {
                bids.put(key, data);
            }
        }

        public void close(){
            if(close != null) {
                close.run();
            }
            bids.clear();
        }  
    }

    public static ResultWrapper handleBid(
            SdanClient advClient,
            SdanBidEvent bid
    ) {

        ResultWrapper res = new ResultWrapper();
        NostrSubscription sub = advClient.handleBid(bid,new SdanAdvSideNegotiation.AdvListener() {

            @Override
            public void onBail(SdanNegotiation neg, SdanBailEvent event) {
                System.out.println("[advertiser] Bailed by offerer: " + event);
                res.set("bail", neg, event);
            }

            @Override
            public void onOffer(SdanNegotiation neg, SdanOfferEvent offer, Runnable acceptOffer) {
                System.out.println("[advertiser] Received offer: " + offer);
                res.set("offer", neg, offer, acceptOffer);
                acceptOffer.run(); // automatically accept the offer for testing
            }

            @Override
            public void onPaymentRequest(SdanNegotiation neg,
                    SdanPaymentRequestEvent event,String invoice, NotifyPayout notifyPayout) {
                System.out.println("[advertiser] Payment request received: " + event);
                res.set("payment_request", event, notifyPayout);
                notifyPayout.call("Payment request received", "preimage123");
            }
            
        });
        
        res.close = () -> {
            sub.close();
        };
        return res;

    }


    public static ResultWrapper fetchBid(
            SdanClient offererClient,
            NostrFilter filter
    ) {
        
        ResultWrapper res = new ResultWrapper();
        NostrSubscription sub = offererClient.listenForBids(List.of(
            filter),
            new SdanOffererSideNegotiation.OfferListener() {

                @Override
                public void onBail(SdanNegotiation neg, SdanBailEvent event) {
                    System.out.println("[offerer] Bailed by advertiser: " + event);
                    res.set("bail", neg, event);

                }

                @Override
                public void verifyPayout(SdanNegotiation neg, SdanPayoutEvent event) {
                    System.out.println("[offerer] Payout verified: " + event);
                    res.set("payout", neg, event);

                }

                @Override
                public void onRequestingPayment(SdanNegotiation neg) {
                    System.out.println("[offerer] Requesting payment for negotiation: " + neg);
                    res.set("request_payment", neg);

                }

                @Override
                public void showAd(SdanNegotiation neg, SdanAcceptOfferEvent acp, Consumer<String> notifyShown) {
                    System.out.println("[offerer] Showing ad for negotiation: " + neg);
                    res.set("show_ad", neg, acp, notifyShown);
                    notifyShown.accept("Ad shown successfully");
                }

                @Override
                public void onBid(SdanOffererSideNegotiation neg,  Runnable makeOffer) {
                    System.out.println("[offerer] Received bid: " + neg);
                    res.set("bid", neg, makeOffer);
                    makeOffer.run(); // automatically make an offer for testing
                }

            });
            res.close = () -> {
                sub.close();              
            };
        return res;

    }
}
