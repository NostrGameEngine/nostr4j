package org.ngengine.nostr4j.rtc;
 
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignalingListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignalingListener.RemoveReason;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCAnnounce;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCAnswer;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCIceCandidate;
import org.ngengine.nostr4j.rtc.signal.signals.NostrRTCOffer;
import org.ngengine.nostr4j.rtc.turn.NostrTURNSettings;
import org.ngengine.nostr4j.utils.NostrUtils;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class NostrRTCSwarm implements NostrRTCSignalingListener, NostrRTCSocketListener{
    private static final Logger logger = Logger.getLogger(NostrRTCSwarm.class.getName());

    private static class PendingConnection {
        Instant createdAt;
        NostrRTCSocket socket;
    }

    private static final Map<NostrRTCAnnounce, PendingConnection> pendingInitiatedConnections = new ConcurrentHashMap<>();

    private static final Map<NostrRTCAnnounce, NostrRTCSocket> connections = new ConcurrentHashMap<>();

    private final NostrRTCLocalPeer localPeer;
    private final NostrRTCSignaling signaling;
    private final NostrRTCSettings settings;
    private final NostrTURNSettings turnSettings;
    private final NostrExecutor executor;
    private final NostrKeyPair roomKeyPair;

    public NostrRTCSwarm(
        NostrRTCSettings settings,
        NostrTURNSettings turnSettings,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrPool signalingPool
    ){
        this.roomKeyPair= Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.turnSettings = Objects.requireNonNull(turnSettings, "Settings cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local peer cannot be null");
        this.signaling = new NostrRTCSignaling(settings, localPeer, roomKeyPair, signalingPool);
        this.signaling.addListener(this);
        Platform platform = NostrUtils.getPlatform();
        this.executor = platform.newPoolExecutor();
    }

    private void loop(){
        this.executor.runLater(()->{
            Instant now = Instant.now();

            // remove expired pending connections
            for (Map.Entry<NostrRTCAnnounce, PendingConnection> entry : pendingInitiatedConnections.entrySet()) {
                PendingConnection conn = entry.getValue();
                if (conn.createdAt.plusSeconds(settings.getPeerExpiration().toSeconds()).isBefore(now)) {
                    logger.warning("Pending connection timed out: " + entry.getKey());
                    conn.socket.close();
                    pendingInitiatedConnections.remove(entry.getKey());
                }
            }

            // remove closed connections
            for (Map.Entry<NostrRTCAnnounce, NostrRTCSocket> entry : connections.entrySet()) {
                NostrRTCSocket conn = entry.getValue();
                if (conn.isClosed()) {
                    logger.warning("Connection closed: " + entry.getKey());
                    connections.remove(entry.getKey());
                }
            }
            return null;
        }, settings.getGcInterval().toMillis(), TimeUnit.MILLISECONDS);
    }


    public boolean hasPrecedenceOverLocal(NostrPublicKey pubkey){
        String localHex = localPeer.getPubkey().asHex();
        String remoteHex = pubkey.asHex();
        return localHex.compareTo(remoteHex) < 0;
    }

    public AsyncTask<Void> start(){
        this.loop();
        return this.signaling.start();
    }

    public void kick(NostrRTCPeer peer){

    }

    public void ban(NostrRTCPeer peer){

    }

    public void unban(NostrRTCPeer peer){

    }




 
    @Override
    public void onAddAnnounce(NostrRTCAnnounce announce) {
        if(pendingInitiatedConnections.get(announce)!=null){
            logger.warning("Already have a pending connection for announce: " + announce);
            return;
        }

        logger.fine("Initiating connection to announce: " + announce);
        PendingConnection conn = new PendingConnection();
        conn.createdAt = Instant.now();
        conn.socket = new NostrRTCSocket(
            executor,
            localPeer,
            roomKeyPair.getPublicKey().asHex(),           
            settings,
            turnSettings
        );

        conn.socket.addListener(this);
        // send offer to remote peer
        conn.socket.listen().then(offer->{
            try {
                this.signaling.sendOffer(offer, announce.getPeerInfo().getPubkey());
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        });

        pendingInitiatedConnections.put(announce, conn);
    }

    @Override
    public void onUpdateAnnounce(NostrRTCAnnounce announce) {
        logger.fine("Update announce: " + announce);
    }

    @Override
    public void onRemoveAnnounce(NostrRTCAnnounce announce, RemoveReason reason) {
       logger.fine("Remove announce: " + announce + " reason: " + reason);
    }

    @Override
    public void onReceiveOffer(NostrRTCAnnounce announce, NostrRTCOffer offer) {
        // offer received from remote peer
        PendingConnection conn = pendingInitiatedConnections.get(announce);
        if(conn!=null){
            if(hasPrecedenceOverLocal(offer.getPeerInfo().getPubkey())){
                // if there is already a connection initiated to this peer, forfeit it if the
                // remote has precedence over local.
                pendingInitiatedConnections.remove(announce);
                conn.socket.close();             
            } else {
                // otherwise, just ignore it
                return;
            }
        }

        NostrRTCSocket socket = new NostrRTCSocket(
            executor,
            localPeer,
            roomKeyPair.getPublicKey().asHex(),           
            settings,
            turnSettings
        );
        socket.addListener(this);

        // send answer to remote peer
        socket.connect(offer).then(answer -> {
            try {
                this.signaling.sendAnswer(answer, offer.getPeerInfo().getPubkey());
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        });
        connections.put(announce, socket);

    }

    @Override
    public void onReceiveAnswer(NostrRTCAnnounce announce, NostrRTCAnswer answer) {
        // answer received from remote peer

        // get the pending initiated connection if any, otherwise just ignore
        PendingConnection conn = pendingInitiatedConnections.remove(announce);
        if(conn!=null){
            // complete the connection
            connections.put(announce, conn.socket);
            conn.socket.connect(answer).then(ignored -> {
                // connection completed
                return null;
            });            
        }

    }

    @Override
    public void onReceiveCandidates(NostrRTCAnnounce announce, NostrRTCIceCandidate candidate) {
        // receive remote candidate, add it to the socket
       NostrRTCSocket socket = connections.get(announce);
       if(socket!=null){
            socket.mergeRemoteRTCIceCandidate(candidate);
       }else{
            logger.warning("No socket found for announce: " + announce);
       }
    }


    @Override
    public void onRTCSocketLocalIceCandidate(NostrRTCSocket socket, NostrRTCIceCandidate candidate) {
        try {
            // receive local candidate, send it to the remote peer
            this.signaling.sendCandidates(
                candidate,
                socket.getRemotePeer().getPubkey()            
            );
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }


    @Override
    public void onRTCSocketMessage(NostrRTCSocket socket, ByteBuffer bbf, boolean turn) {
        // receive message from remote peer
        
    }

}