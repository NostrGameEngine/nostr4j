/**
 * BSD 3-Clause License
 * 
 * Copyright (c) 2025, Riccardo Balbo
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.nostr4j.rtc;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnnounce;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCIceCandidate;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOffer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.rtc.turn.NostrTURNSettings;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRTCSwarm implements NostrRTCSignaling.Listener, NostrRTCSocketListener {

    private static final Logger logger = Logger.getLogger(NostrRTCSwarm.class.getName());

    private static class PendingConnection {

        Instant createdAt;
        NostrRTCSocket socket;
    }

    private final Map<NostrPublicKey, PendingConnection> pendingInitiatedConnections = new ConcurrentHashMap<>();
    private final Map<NostrPublicKey, NostrRTCSocket> connections = new ConcurrentHashMap<>();
    private final Collection<NostrPublicKey> bannedPeers = new CopyOnWriteArrayList<>();

    private final List<NostrRTCSwarmOnPeerConnection> onConnectionListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCSwarmOnPeerDisconnection> onDisconnectionListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCSwarmOnPeerMessage> onMessageListeners = new CopyOnWriteArrayList<>();

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
    ) {
        this.roomKeyPair = Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.turnSettings = Objects.requireNonNull(turnSettings, "Settings cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local peer cannot be null");
        this.signaling = new NostrRTCSignaling(settings, localPeer, roomKeyPair, signalingPool);
        this.signaling.addListener(this);
        Platform platform = NostrUtils.getPlatform();
        this.executor = platform.newPoolExecutor();
    }

    public void addListener(NostrRTCSwarmListener listener) {
        if (listener instanceof NostrRTCSwarmOnPeerConnection) {
            this.onConnectionListeners.add((NostrRTCSwarmOnPeerConnection) listener);
        }
        if (listener instanceof NostrRTCSwarmOnPeerDisconnection) {
            this.onDisconnectionListeners.add((NostrRTCSwarmOnPeerDisconnection) listener);
        }
        if (listener instanceof NostrRTCSwarmOnPeerMessage) {
            this.onMessageListeners.add((NostrRTCSwarmOnPeerMessage) listener);
        }
    }

    public void removeListener(NostrRTCSwarmListener listener) {
        if (listener instanceof NostrRTCSwarmOnPeerConnection) {
            this.onConnectionListeners.remove(listener);
        }
        if (listener instanceof NostrRTCSwarmOnPeerDisconnection) {
            this.onDisconnectionListeners.remove(listener);
        }
        if (listener instanceof NostrRTCSwarmOnPeerMessage) {
            this.onMessageListeners.remove(listener);
        }
    }

    private void loop() {
        this.executor.runLater(
                () -> {
                    try {
                        Instant now = Instant.now();

                        // remove expired pending connections
                        for (Map.Entry<NostrPublicKey, PendingConnection> entry : pendingInitiatedConnections.entrySet()) {
                            PendingConnection conn = entry.getValue();
                            if (conn.createdAt.plusSeconds(settings.getPeerExpiration().toSeconds()).isBefore(now)) {
                                logger.warning("Pending connection timed out: " + entry.getKey());
                                conn.socket.close();
                                pendingInitiatedConnections.remove(entry.getKey());
                            }
                        }

                        // try to connect to every announced peer
                        Collection<NostrRTCAnnounce> announces = this.signaling.getAnnounces();
                        for (NostrRTCAnnounce announce : announces) {
                            NostrPublicKey remotePubkey = announce.getPubkey();
                            boolean isConnected = connections.containsKey(remotePubkey);
                            if (isConnected) continue;

                            boolean isPending = pendingInitiatedConnections.containsKey(remotePubkey);
                            if (isPending) continue;

                            if (!shouldOfferConnection(remotePubkey)) continue;

                            logger.fine("Initiating connection to: " + remotePubkey);

                            PendingConnection conn = new PendingConnection();
                            conn.createdAt = Instant.now();
                            conn.socket =
                                new NostrRTCSocket(
                                    executor,
                                    localPeer,
                                    roomKeyPair.getPublicKey().asHex(),
                                    settings,
                                    turnSettings
                                );

                            conn.socket.addListener(this);

                            // send offer to remote peer
                            conn.socket
                                .listen()
                                .then(offer -> {
                                    try {
                                        this.signaling.sendOffer(offer, remotePubkey);
                                    } catch (Exception e) {
                                        // e.printStackTrace();
                                        logger.log(Level.WARNING, "Error sending offer", e);
                                    }
                                    return null;
                                });

                            pendingInitiatedConnections.put(remotePubkey, conn);
                        }
                    } catch (Exception e) {
                        logger.warning("Error in loop: " + e.getMessage());
                    }

                    this.loop();
                    return null;
                },
                1000,
                TimeUnit.MILLISECONDS
            );
    }

    public boolean shouldOfferConnection(NostrPublicKey pubkey) {
        String localHex = localPeer.getPubkey().asHex();
        String remoteHex = pubkey.asHex();
        boolean precedence = localHex.compareTo(remoteHex) < 0;
        if (precedence) {
            logger.fine("Local peer has precedence over remote peer: " + localHex + " < " + remoteHex);
        } else {
            logger.fine("Remote peer has precedence over local peer: " + localHex + " > " + remoteHex);
        }

        return precedence;
    }

    public AsyncTask<Void> start() {
        this.loop();
        return this.signaling.start();
    }

    public void kick(NostrPublicKey peer) {
        // forcefully disconnect a peer
        NostrRTCSocket socket = connections.remove(peer);
        if (socket != null) {
            logger.fine("Kicking peer: " + peer);
            socket.close();
            for (NostrRTCSwarmOnPeerDisconnection listener : onDisconnectionListeners) {
                listener.onSwarmPeerDisconnected(socket.getRemotePeer().getPubkey(), socket);
            }
        } else {
            logger.warning("No socket found for peer: " + peer);
        }
    }

    public void ban(NostrPublicKey peer) {
        if (!bannedPeers.contains(peer)) {
            logger.fine("Banning peer: " + peer);
            bannedPeers.add(peer);
        } else {
            logger.fine("Peer already banned: " + peer);
        }
        kick(peer);
    }

    public void unban(NostrPublicKey peer) {
        logger.fine("Unbanning peer: " + peer);
        bannedPeers.remove(peer);
    }

    @Override
    public void onAddAnnounce(NostrRTCAnnounce announce) {}

    @Override
    public void onUpdateAnnounce(NostrRTCAnnounce announce) {
        logger.fine("Update announce: " + announce);
    }

    @Override
    public void onRemoveAnnounce(NostrRTCAnnounce announce, RemoveReason reason) {
        NostrPublicKey remotePubkey = announce.getPubkey();
        logger.fine("Remove announce: " + announce + " reason: " + reason);
        // close connections
        NostrRTCSocket socket = connections.remove(remotePubkey);
        if (socket != null) {
            socket.close();
            for (NostrRTCSwarmOnPeerDisconnection listener : onDisconnectionListeners) {
                listener.onSwarmPeerDisconnected(socket.getRemotePeer().getPubkey(), socket);
            }
        }

        PendingConnection conn = pendingInitiatedConnections.remove(remotePubkey);
        if (conn != null) {
            conn.socket.close();
        }
    }

    public NostrRTCPeer getRemotePeerInfo(NostrPublicKey pubkey) {
        NostrRTCPeer peer = connections.get(pubkey).getRemotePeer();
        if (peer != null) {
            return peer;
        }
        return null;
    }

    public NostrRTCPeer getLocalPeerInfo() {
        return this.localPeer;
    }

    @Override
    public void onReceiveOffer(NostrRTCOffer offer) {
        // NostrRTCPeer remotePeer = offer.getPeerInfo();

        NostrPublicKey remotePubkey = offer.getPeerInfo().getPubkey();
        // offer received from remote peer
        PendingConnection conn = pendingInitiatedConnections.get(remotePubkey);
        if (conn != null) {
            // if (hasPrecedenceOverLocal(remotePubkey)) {
            // if there is already a connection initiated to this peer, forfeit it if the
            // remote has precedence over local.
            pendingInitiatedConnections.remove(remotePubkey);
            logger.fine(
                "Forfeiting connection to peer: " +
                remotePubkey +
                " because remote peer has precedence over local peer and is initiating the connection"
            );
            conn.socket.close();
            // } else {
            //     logger.fine("Already have a pending connection for peer: " + remotePubkey + " with local peer precedence");
            //     // otherwise, just ignore it
            //     return;
            // }
        }

        logger.fine("Connecting to peer: " + remotePubkey);
        NostrRTCSocket socket = new NostrRTCSocket(
            executor,
            localPeer,
            roomKeyPair.getPublicKey().asHex(),
            settings,
            turnSettings
        );
        socket.addListener(this);

        // send answer to remote peer
        socket
            .connect(offer)
            .then(answer -> {
                try {
                    logger.fine("Sending answer to remote peer: " + remotePubkey);
                    this.signaling.sendAnswer(answer, remotePubkey);
                } catch (Exception e) {
                    // e.printStackTrace();
                    logger.log(Level.WARNING, "Error sending answer", e);
                }
                return null;
            });
        connections.put(remotePubkey, socket);
        for (NostrRTCSwarmOnPeerConnection listener : onConnectionListeners) {
            listener.onSwarmPeerConnected(socket.getRemotePeer().getPubkey(), socket);
        }
    }

    @Override
    public void onReceiveAnswer(NostrRTCAnswer answer) {
        // answer received from remote peer

        NostrPublicKey remotePubkey = answer.getPeerInfo().getPubkey();

        // get the pending initiated connection if any, otherwise just ignore
        PendingConnection conn = pendingInitiatedConnections.remove(remotePubkey);
        if (conn != null) {
            logger.fine("Received answer, finalizing connection to peer: " + remotePubkey);
            // complete the connection
            conn.socket
                .connect(answer)
                .then(ignored -> {
                    logger.fine("Connected to peer: " + remotePubkey);
                    // connection completed
                    return null;
                });
            connections.put(remotePubkey, conn.socket);

            for (NostrRTCSwarmOnPeerConnection listener : onConnectionListeners) {
                listener.onSwarmPeerConnected(remotePubkey, conn.socket);
            }
        } else {
            // if there is no pending connection, just ignore it
            logger.warning("No pending connection for peer: " + remotePubkey);
        }
    }

    @Override
    public void onReceiveCandidates(NostrRTCIceCandidate candidate) {
        logger.fine("Received ICE candidate: " + candidate);
        NostrPublicKey remotePubkey = candidate.getPubkey();

        // receive remote candidate, add it to the socket
        NostrRTCSocket socket = connections.get(remotePubkey);
        if (socket != null) {
            socket.mergeRemoteRTCIceCandidate(candidate);
        } else {
            logger.warning("No socket found for peer: " + remotePubkey);
        }
    }

    @Override
    public void onRTCSocketLocalIceCandidate(NostrRTCSocket socket, NostrRTCIceCandidate candidate) {
        try {
            NostrRTCPeer remotePeer = socket.getRemotePeer();
            if (remotePeer == null) return;
            NostrPublicKey pubkey = remotePeer.getPubkey();
            if (pubkey == null) return;
            // if (!socket.isConnected()) return;
            // logger.fine("Received local ICE candidate: " + candidate + " sending to remote peer");
            // receive local candidate, send it to the remote peer
            this.signaling.sendCandidates(candidate, pubkey);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            // e.printStackTrace();
            logger.log(Level.WARNING, "Error sending local ICE candidate", e);
        }
    }

    @Override
    public void onRTCSocketMessage(NostrRTCSocket socket, ByteBuffer bbf, boolean turn) {
        // receive message from remote peer
        for (NostrRTCSwarmOnPeerMessage listener : onMessageListeners) {
            listener.onSwarmPeerMessage(socket.getRemotePeer().getPubkey(), socket, bbf, turn);
        }

        byte bbfArray[] = new byte[bbf.remaining()];
        bbf.get(bbfArray);
        bbf.flip();
        logger.fine(
            "Received message from peer: " +
            socket.getRemotePeer().getPubkey() +
            " : " +
            new String(bbfArray) +
            " turn: " +
            turn
        );
    }

    public void send(NostrPublicKey peer, ByteBuffer bbf) {
        NostrRTCSocket socket = connections.get(peer);
        if (socket != null) {
            socket.write(bbf);
        } else {
            logger.warning("No socket found for peer: " + peer);
        }
    }

    public void broadcast(ByteBuffer bbf) {
        for (NostrRTCSocket socket : connections.values()) {
            socket.write(bbf);
        }
    }
}
