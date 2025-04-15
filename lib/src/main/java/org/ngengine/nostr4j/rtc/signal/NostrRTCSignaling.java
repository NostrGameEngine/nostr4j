package org.ngengine.nostr4j.rtc.signal;

import java.io.Closeable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;
import org.ngengine.nostr4j.nip44.Nip44;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.rtc.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.NostrRTCSettings;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCAnnounce;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCAnswer;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCIceCandidate;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCOffer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.transport.NostrMessageAck;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRTCSignaling implements NostrSubEventListener, Closeable {
 
    private static final Logger logger = Logger.getLogger(NostrRTCSignaling.class.getName());
    private final NostrPool pool;
    private final NostrKeyPair roomKeyPair;
    private final NostrRTCLocalPeer localPeer;
    private final NostrKeyPairSigner roomSigner;
    private final Map<NostrPublicKey, NostrRTCAnnounce> seenAnnounces = new HashMap<>();
    private final List<NostrRTCSignalingListener> listeners = new CopyOnWriteArrayList<>();
    private final NostrRTCSettings settings;
    private final NostrExecutor executor;

    private volatile boolean closed = false;    
    private NostrSubscription sub1;
    private NostrSubscription sub2;
    
    
    public NostrRTCSignaling(
        NostrRTCSettings settings,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrPool pool
    )  {
        this.pool = pool;
        this.roomKeyPair = roomKeyPair;
        this.roomSigner = new NostrKeyPairSigner(roomKeyPair);
        this.localPeer = localPeer;
        this.settings = settings;
        this.executor = NostrUtils.getPlatform().newPoolExecutor();
    }

    public void addListener(NostrRTCSignalingListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NostrRTCSignalingListener listener) {
        listeners.remove(listener);
    }    
 
    // internal- runs in executor thread
    @Override
    public void onSubEvent(SignedNostrEvent event, boolean stored) {
        if(closed) return;
        this.executor.run(()->{
            String type = event.getTag("t")[1];
            switch(type){
                case "connect":{
                    seenAnnounces.compute(event.getPubkey(), (k,v)->{
                        if(v==null){
                            NostrRTCAnnounce a = new NostrRTCAnnounce(event.getPubkey(), event.getExpirationTimestamp());
                            for (NostrRTCSignalingListener listener : listeners) {
                                listener.onAddAnnounce(a);
                            }
                            return a;
                        } else{
                            v.updateExpireAt(event.getExpirationTimestamp());
                            for (NostrRTCSignalingListener listener : listeners) {
                                listener.onUpdateAnnounce(v);
                            }
                            return v;
                        }
                    });
                return null;
                }
                case "disconnect":{
                    NostrRTCAnnounce announce = seenAnnounces.remove(event.getPubkey());
                    if(announce!=null){
                        for (NostrRTCSignalingListener listener : listeners) {
                            listener.onRemoveAnnounce(announce, NostrRTCSignalingListener.RemoveReason.DISCONNECTED);
                        }
                    }
                    return null;
                }
            }

            Platform platform = NostrUtils.getPlatform();
            decrypt(event.getContent(),event.getPubkey()).then(decryptedContent ->{
                try{
                    Map<String, Object> content = platform.fromJSON(decryptedContent, Map.class);
                    switch (type) {
                        case "offer": {
                            NostrRTCAnnounce announce = seenAnnounces.get(event.getPubkey());
                            if(announce!=null){
                                NostrRTCOffer offer = new NostrRTCOffer(event.getPubkey(),content);
                                for (NostrRTCSignalingListener listener : listeners) {
                                    listener.onReceiveOffer(announce, offer);
                                }
                            } else{
                                logger.warning("Received offer from unknown announce: "+event.getPubkey());                            
                            }

                            return null;
                        }
                        case "answer": {
                            NostrRTCAnnounce announce = seenAnnounces.get(event.getPubkey());
                            if(announce!=null){
                                NostrRTCAnswer answer = new NostrRTCAnswer(event.getPubkey(),content);
                                for (NostrRTCSignalingListener listener : listeners) {
                                    listener.onReceiveAnswer(announce, answer);
                                }
                            } else{
                                logger.warning("Received answer from unknown announce: "+event.getPubkey());                            
                            }

                            return null;
                        } 
                        case "candidate": {
                            NostrRTCAnnounce announce = seenAnnounces.get(event.getPubkey());
                            if(announce!=null){
                                NostrRTCIceCandidate candidate = new NostrRTCIceCandidate(content);
                                for (NostrRTCSignalingListener listener : listeners) {
                                    listener.onReceiveCandidates(announce, candidate);
                                }
                            } else{
                                logger.warning("Received candidates from unknown announce: "+event.getPubkey());                            
                            }                       
                            return null;
                        }
                    }
                    logger.warning("Unknown event type: " + type);
                    return null;
                } catch (Exception e) {
                    logger.warning("Error processing event: " + e.getMessage());
                    return null;
                }
            }).catchException(exc->{
                logger.warning("Error decrypting event: "+exc.getMessage());           
            });
            return null;
        });


    }

    public AsyncTask<Void> start(){
        this.closed = false;
        Platform platform = NostrUtils.getPlatform();
        NostrPublicKey localpk= this.localPeer.getPubkey();
        this.sub1 = this.pool.subscribe(new NostrFilter().kind(25050).tag("r", roomKeyPair.getPublicKey().asHex()).tag("t", "connect","disconnect"));
        this.sub2 = this.pool.subscribe(new NostrFilter().kind(25050).tag("r", roomKeyPair.getPublicKey().asHex()).tag("t", "offer","answer","candidate").tag("p", localpk.asHex()));
        this.sub1.listenEvent(this);
        this.sub2.listenEvent(this);
        AsyncTask<List<NostrMessageAck>> open1 = this.sub1.open();
        AsyncTask<List<NostrMessageAck>> open2 = this.sub2.open();
        return platform.awaitAll(List.of(open1, open2)).compose(acks->{
            this.loop();
            return platform.wrapPromise((res,rej)->{
                res.accept(null);
            });
        });
    }


    // internal- runs in executor thread
    private void loop(){  
        this.executor.runLater(() -> {
            try {
                this.sendAnnounce().await();
            } catch (Exception e) {
                logger.warning("Error in loop: " + e.getMessage());
            }

            // remove all expired announces
            Instant now = Instant.now();
            seenAnnounces.entrySet().removeIf(e -> {
                if (e.getValue().getExpireAt().isBefore(now)) {
                    for (NostrRTCSignalingListener listener : listeners) {
                        listener.onRemoveAnnounce(e.getValue(), NostrRTCSignalingListener.RemoveReason.EXPIRED);
                    }
                    return true;
                }
                return false;
            });

            if (closed) return null;
            this.loop();
            return null;
        }, settings.getAnnounceInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public AsyncTask<List<NostrMessageAck>> sendAnnounce(){
        AsyncTask<List<NostrMessageAck>> out = null;
        UnsignedNostrEvent connectEvent = new UnsignedNostrEvent();
        connectEvent.setKind(25050);
        connectEvent.setCreatedAt(Instant.now());
        connectEvent.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        connectEvent.setTag("t", "connect");
        connectEvent.setTag("expiration", String.valueOf(Instant.now().plusSeconds(60).getEpochSecond()));
        out = this.localPeer.getSigner().sign(connectEvent).compose(ev->{
            return pool.send(ev);
        });          
        return out;
    }

    public AsyncTask<List<NostrMessageAck>> sendOffer(NostrRTCOffer offer, NostrPublicKey recipient) throws Exception{
        if(this.closed) throw new IllegalStateException("Already closed");

 
        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.setKind(25050);
        event.setCreatedAt(Instant.now());
        event.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.setTag("t", "offer");
        event.setTag("p", recipient.asHex());

        Platform platform = NostrUtils.getPlatform();
        Map<String, String> content = Map.of(
            "offer", offer.getOfferString()       
        );

        String json = platform.toJSON(content);

        return encrypt(json, recipient).compose(encContent->{
            event.setContent(encContent);
            return this.localPeer.getSigner().sign(event).compose(ev->{
                return pool.send(ev);
            });
        });
    }

    public AsyncTask<List<NostrMessageAck>> sendAnswer(NostrRTCAnswer answer, NostrPublicKey recipient) throws Exception {
        if (this.closed)
            throw new IllegalStateException("Already closed");

        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.setKind(25050);
        event.setCreatedAt(Instant.now());
        event.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.setTag("t", "answer");
        event.setTag("p", recipient.asHex());

        Platform platform = NostrUtils.getPlatform();
        Map<String, String> content = Map.of(
                "sdp", answer.getSdp());

        String json = platform.toJSON(content);

        return encrypt(json, recipient).compose(encContent -> {
            event.setContent(encContent);
            return this.localPeer.getSigner().sign(event).compose(ev -> {
                return pool.send(ev);
            });
        });
    }

    public AsyncTask<List<NostrMessageAck>> sendCandidates(NostrRTCIceCandidate candidate, NostrPublicKey recipient) throws Exception {
        Collection<String> candidates = candidate.getCandidates();
        if (this.closed) throw new IllegalStateException("Already closed");

        UnsignedNostrEvent event = new UnsignedNostrEvent();
        event.setKind(25050);
        event.setCreatedAt(Instant.now());
        event.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        event.setTag("t", "candidate");
        event.setTag("p", recipient.asHex());

        Platform platform = NostrUtils.getPlatform();
        Map<String, Object> content = Map.of(
                "candidates", candidates
        );
        String json = platform.toJSON(content);
        return encrypt(json, recipient).compose(encContent -> {
            event.setContent(encContent);
            return this.localPeer.getSigner().sign(event).compose(ev -> {
                return pool.send(ev);
            });
        });
    }

    public void  close(){
        if(this.closed) throw new IllegalStateException("Already closed");
        this.closed = true;
        UnsignedNostrEvent connectEvent = new UnsignedNostrEvent();
        connectEvent.setKind(25050);
        connectEvent.setCreatedAt(Instant.now());
        connectEvent.setTag("r", this.roomKeyPair.getPublicKey().asHex());
        connectEvent.setTag("t", "disconnect");
        this.localPeer.getSigner().sign(connectEvent).compose(ev -> {
            return pool.send(ev);
        });
        this.sub1.close();
        this.sub2.close();
        this.pool.close().forEach(r -> r.disconnect("closed by peer"));
        this.executor.close();
    }
 

    private AsyncTask<String> encrypt(String content, NostrPublicKey recipient){
        AsyncTask<String> out = this.localPeer.getSigner().encrypt(content, recipient).compose(enc->{
            return roomSigner.encrypt(enc, recipient);           
        });
        return out;
    }

    private AsyncTask<String> decrypt(String content, NostrPublicKey sender){      
        AsyncTask<String> out = this.localPeer.getSigner().decrypt(content, roomKeyPair.getPublicKey()).compose(enc->{
            return this.localPeer.getSigner().decrypt(enc, sender);            
        });
        return out;
    }

    
    
}
