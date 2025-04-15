package org.ngengine.nostr4j.rtc;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.tracker.PassthroughEventTracker;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCAnnounce;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCAnswer;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCIceCandidate;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCOffer;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCSignal;
import org.ngengine.nostr4j.rtc.turn.NostrTURN;
import org.ngengine.nostr4j.rtc.turn.NostrTURNListener;
import org.ngengine.nostr4j.rtc.turn.NostrTURNSettings;
import org.ngengine.nostr4j.transport.rtc.RTCTransport;
import org.ngengine.nostr4j.transport.rtc.RTCTransportListener;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRTCSocket implements RTCTransportListener, NostrTURNListener, Closeable {
    private static final Logger logger = Logger.getLogger(NostrRTCSocket.class.getName());

    private final List<NostrRTCSocketListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> localIceCandidates = new CopyOnWriteArrayList<>();
    

    private final String connectionId;
    private final NostrRTCSettings settings;
    private final NostrTURNSettings turnSettings;
    private final NostrExecutor executor;

    private final NostrRTCLocalPeer localPeer;
    private final Instant createdAt = Instant.now();

    
    private RTCTransport transport;
    private NostrTURN turn;
    private NostrRTCPeer remotePeer;


    private volatile boolean useTURN = false;
    private volatile boolean connected = false;
    private volatile AsyncTask<Void> delayedCandidateEmission;
   

    public NostrRTCSocket(
        NostrExecutor executor,   
        NostrRTCLocalPeer localPeer,

        String connectionId,
        NostrRTCSettings settings,
        NostrTURNSettings turnSettings
    ) {
        this.executor= Objects.requireNonNull(executor, "Executor cannot be null");
        this.connectionId = Objects.requireNonNull(connectionId, "Connection ID cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local Peer cannot be null");
        this.turnSettings= Objects.requireNonNull(turnSettings, "TURN Settings cannot be null");
     }

     public NostrRTCLocalPeer getLocalPeer() {
        return localPeer;
     }

     public NostrRTCPeer getRemotePeer() {
        return remotePeer;
     }


     public boolean isConnected(){
        return connected;
     }

     public Instant getCreatedAt() {
        return createdAt;
     }

     public void close(){
        if(this.transport!=null)try {
            this.transport.close();
        } catch (Exception e) {
            logger.severe("Error closing transport: " + e.getMessage());
        }
        if(this.turn!=null)try {
            this.turn.close();
        } catch (Exception e) {
            logger.severe("Error closing TURN: " + e.getMessage());
        }
        if(delayedCandidateEmission!=null){
            delayedCandidateEmission.cancel();
        }
        delayedCandidateEmission = null;
        localIceCandidates.clear();
        listeners.clear();
     }

     public void addListener(NostrRTCSocketListener listener) {
      
        listeners.add(listener);
    }

    public void removeListener(NostrRTCSocketListener listener) {
    
        listeners.remove(listener);
    }

    private void emitCandidates(){
        if(delayedCandidateEmission!=null){
            delayedCandidateEmission.cancel();
        }

        delayedCandidateEmission = this.executor.runLater(
            ()->{
                NostrRTCIceCandidate iceCandidate= new NostrRTCIceCandidate(new ArrayList<String>(localIceCandidates), new HashMap<String,Object>());
                for(NostrRTCSocketListener listener : listeners) {
                    listener.onRTCSocketLocalIceCandidate(this, iceCandidate);
                }
                return null;
            },
            1, TimeUnit.SECONDS);
    }

    AsyncTask<NostrRTCOffer> listen(){
        if (this.transport != null)
            throw new IllegalStateException("Already connected");

        useTURN(false);

        Platform platform = NostrUtils.getPlatform();
        this.transport = platform.newRTCTransport(connectionId, localPeer.getStunServers());
        this.transport.addListener(this);

        return this.transport.initiateChannel().then(offerString->{
            return new NostrRTCOffer(localPeer.getPubkey(), offerString, this.localPeer.getTurnServer(), this.localPeer.getMisc());
        });
    }

    AsyncTask<NostrRTCAnswer> connect(NostrRTCSignal offerOrAnswer){
        if(this.transport!=null) throw new IllegalStateException("Already connected");
        useTURN(false);

        Platform platform = NostrUtils.getPlatform();
        this.transport = platform.newRTCTransport(connectionId, localPeer.getStunServers());
        this.transport.addListener(this);
        
        String connectString;
        if(offerOrAnswer instanceof NostrRTCOffer){
            this.remotePeer = Objects.requireNonNull(((NostrRTCOffer)offerOrAnswer).getPeerInfo(), "Remote Peer cannot be null");
            connectString = ((NostrRTCOffer)offerOrAnswer).getOfferString();
        } else if(offerOrAnswer instanceof NostrRTCAnswer){
            this.remotePeer = Objects.requireNonNull(((NostrRTCAnswer) offerOrAnswer).getPeerInfo(),
                    "Remote Peer cannot be null");
            connectString = ((NostrRTCAnswer) offerOrAnswer).getSdp();
        } else {
            throw new IllegalArgumentException("Invalid RTC signal type");
        }

        this.turn = new NostrTURN(connectionId, localPeer, remotePeer, turnSettings);
        this.turn.addListener(this);
        this.turn.start();

        return this.transport.connectToChannel(connectString).then(answerString->{
            
            if(answerString== null)return null;
            NostrRTCAnswer answer = new NostrRTCAnswer(localPeer.getPubkey(), answerString, this.localPeer.getTurnServer(), this.localPeer.getMisc());
            return answer;
        });
    }

    
    public void mergeRemoteRTCIceCandidate(NostrRTCIceCandidate candidate){
        if(this.transport!=null){
            this.transport.addRemoteIceCandidates(candidate.getCandidates());
        }
    }

    @Override
    public void onLocalRTCIceCandidate(String candidateString) {      
        localIceCandidates.addIfAbsent(candidateString);
        emitCandidates();
    }

    @Override
    public void onLinkEstablished() {
        connected = true;
        useTURN(false);
    }

    @Override
    public void onLinkLost() {
        connected = true;
        useTURN(true);
    }

    public void useTURN(boolean use) {
        if (use == useTURN) return;
        this.useTURN = use;         
    } 

    @Override
    public void onRTCBinaryMessage(ByteBuffer bbf) {
        for(NostrRTCSocketListener listener : listeners) {
            listener.onRTCSocketMessage(this, bbf, false);
        }
    }

    @Override
    public void onTurnPacket(NostrRTCPeer peer, ByteBuffer data) {
        for(NostrRTCSocketListener listener : listeners) {
            listener.onRTCSocketMessage(this, data, true);
        }
    }

    public void write(ByteBuffer bbf){
        if(this.useTURN){
            this.turn.write(bbf);
        }else{
            this.transport.send(bbf);
        }
    }

    @Override
    public void onRTCChannelClosed() {
        logger.info("RTC Channel Closed");
        useTURN(true);
    }

    @Override
    public void onRTCChannelError(Throwable e) {
        logger.severe("RTC Channel Error "+ e);
    }

  

 
}