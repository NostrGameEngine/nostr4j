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

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCPeerSocketAvailableListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerDisconnectListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerDiscoveredListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerMessageListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswerSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.rtc.turn.NostrTURNPool;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

import jakarta.annotation.Nullable;

public class NostrRTCRoom implements Closeable {

    private static final Logger logger = Logger.getLogger(NostrRTCRoom.class.getName());

    private final Map<NostrRTCPeer, NostrRTCSocket> connections = new ConcurrentHashMap<>();
    private final Map<NostrRTCChannel, BlockingPacketQueue<ByteBuffer>> pendingSends = new ConcurrentHashMap<>();
    private final Collection<NostrPublicKey> bannedPeers = new CopyOnWriteArrayList<>();

    private final List<NostrRTCPeerSocketAvailableListener> onSocketAvailable = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerDisconnectListener> onDisconnectionListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerMessageListener> onMessageListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerDiscoveredListener> onPeerDiscoveredListeners = new CopyOnWriteArrayList<>();

    private final NostrRTCLocalPeer localPeer;
    private final NostrRTCSignaling signaling;
    private final RTCSettings settings;
    private final AsyncExecutor executor;
    private final NostrKeyPair roomKeyPair;
    private final String turnServerUrl;
    private final NostrTURNPool turnPool;
    private volatile boolean forceTURN = false;

    private void drainQueue(NostrRTCChannel channel){
        BlockingPacketQueue<ByteBuffer> queue = pendingSends.get(channel);
        if(queue!=null){
            queue.restart();
        }
    }
    private static interface Listener extends NostrRTCSignaling.Listener, NostrRTCSocketListener, NostrRTCChannelListener {}

    private final Listener listener = new Listener() {
        @Override
        public void onAddAnnounce(NostrRTCConnectSignal announce) {
            NostrRTCRoom.this.onAddAnnounce(announce);
        }

        @Override
        public void onUpdateAnnounce(NostrRTCConnectSignal announce) {
            NostrRTCRoom.this.onUpdateAnnounce(announce);
        }

        @Override
        public void onRTCSocketClose(NostrRTCSocket socket) {
            NostrRTCRoom.this.onRTCSocketClose(socket);
        }

        @Override
        public void onReceiveOffer(NostrRTCOfferSignal offer) {
            NostrRTCRoom.this.onReceiveOffer(offer);
        }

        @Override
        public void onReceiveAnswer(NostrRTCAnswerSignal answer) {
            NostrRTCRoom.this.onReceiveAnswer(answer);
        }

        @Override
        public void onReceiveCandidates(NostrRTCRouteSignal candidate) {
            NostrRTCRoom.this.onReceiveCandidates(candidate);
        }

        @Override
        public void onRemoveAnnounce(NostrRTCConnectSignal announce, RemoveReason reason) {
            NostrRTCRoom.this.onRemoveAnnounce(announce, reason);
        }

        @Override
        public void onRTCSocketRouteUpdate(
            NostrRTCSocket socket,
            Collection<RTCTransportIceCandidate> candidates,
            String turnServer
        ) {
            NostrRTCRoom.this.onRTCSocketLocalIceCandidate(socket, candidates, turnServer);
        }
        
        @Override
        public void onRTCChannel(NostrRTCChannel channel) {
            channel.addListener(this);
            drainQueue(channel);
        }
        
        @Override
        public void onRTCChannelReady(NostrRTCChannel channel) {
            // channel.addListener(this);
            drainQueue(channel);
        }

        @Override
        public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean turn) {
            NostrRTCSocket socket = channel.getSocket();
            NostrRTCPeer remotePeer = socket.getRemotePeer();
            if (remotePeer == null || remotePeer.getPubkey() == null) return;
            for (NostrRTCRoomPeerMessageListener listener : onMessageListeners) {
                try {
                    listener.onRoomPeerMessage(remotePeer, socket, channel, bbf, turn);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying listener", e);
                }
            }
        }

        @Override
        public void onRTCChannelError(NostrRTCChannel channel, Throwable e) {
            //  NostrRTCSocket socket = channel.getSocket();
            // NostrPublicKey remotePubkey = socket.getRemotePeer().getPubkey();
            // for (NostrRTCRoomPeerMessageListener listener : onMessageListeners) {
            //     try {
            //         listener.onRTCChannelError(remotePubkey, socket, channel, e);
            //     } catch (Exception xe) {
            //         logger.log(Level.WARNING, "Error notifying listener", xe);
            //     }
            // }
        }

        @Override
        public void onRTCChannelClosed(NostrRTCChannel channel) {
            //  NostrRTCSocket socket = channel.getSocket();
            // NostrPublicKey remotePubkey = socket.getRemotePeer().getPubkey();
            // for (NostrRTCRoomPeerMessageListener listener : onMessageListeners) {
            //     try {
            //         listener.onRTCChannelClosed(remotePubkey, socket, channel);
            //     } catch (Exception e) {
            //         logger.log(Level.WARNING, "Error notifying listener", e);
            //     }
            // }
        }

        @Override
        public void onRTCBufferedAmountLow(NostrRTCChannel channel) {
            NostrRTCSocket socket = channel.getSocket();
            NostrRTCPeer remotePeer = socket.getRemotePeer();
            if (remotePeer == null || remotePeer.getPubkey() == null) return;
            
            for (NostrRTCRoomPeerMessageListener listener : onMessageListeners) {
                try {
                    listener.onRoomPeerBufferedAmountLow(remotePeer, socket, channel);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying listener", e);
                }
            }
            drainQueue(channel);
        }
    };

    public NostrRTCRoom(
        RTCSettings settings,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrPool signalingPool,
        String turnServerUrl,
        NostrTURNPool turnPool
    ) {
        this.roomKeyPair = Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local peer cannot be null");
        this.turnServerUrl = turnServerUrl;
        this.turnPool = turnPool;
        this.signaling =
            new NostrRTCSignaling(
                settings,
                localPeer.getApplicationId(),
                localPeer.getProtocolId(),
                localPeer,
                roomKeyPair,
                Objects.requireNonNull(signalingPool, "Signaling pool cannot be null")
            );
        this.signaling.addListener(listener);
        this.executor = NGEUtils.getPlatform().newAsyncExecutor(NostrRTCRoom.class);
    }

    private NostrRTCSocket newSocket(NostrRTCPeer remotePeer) {
        NostrRTCSocket socket = new NostrRTCSocket(executor, remotePeer, roomKeyPair, localPeer, settings, turnServerUrl, turnPool);
        socket.setForceTURN(forceTURN);
        return socket;
    }

    @Override
    public void close() {
        // close everything
        for (NostrRTCSocket socket : connections.values()) {
            try {
                socket.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing socket", e);
            }
        }
        for (BlockingPacketQueue<ByteBuffer> queue : pendingSends.values()) {
            try {
                queue.close();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error closing pending send queue", e);
            }
        }
        pendingSends.clear();
        try {
            this.signaling.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error closing signaling", e);
        }
        try {
            this.executor.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error closing executor", e);
        }
    }

    public NostrRTCRoom addMessageListener(NostrRTCRoomPeerMessageListener listener) {
        this.onMessageListeners.add(listener);
        return this;
    }

    public NostrRTCRoom addPeerSocketAvailableListener(NostrRTCPeerSocketAvailableListener listener) {
        this.onSocketAvailable.add(listener);
        return this;
    }

    public NostrRTCRoom addDisconnectionListener(NostrRTCRoomPeerDisconnectListener listener) {
        this.onDisconnectionListeners.add(listener);
        return this;
    }

    public NostrRTCRoom addPeerDiscoveryListener(NostrRTCRoomPeerDiscoveredListener listener) {
        this.onPeerDiscoveredListeners.add(listener);
        return this;
    }

    public void setForceTURN(boolean forceTURN) {
        this.forceTURN = forceTURN;
        for (NostrRTCSocket socket : connections.values()) {
            socket.setForceTURN(forceTURN);
        }
    }

    public boolean isForceTURN() {
        return forceTURN;
    }

    public NostrRTCRoom addListener(NostrRTCRoomListener listener) {
        if (listener instanceof NostrRTCPeerSocketAvailableListener) {
            this.addPeerSocketAvailableListener((NostrRTCPeerSocketAvailableListener) listener);
        }
        if (listener instanceof NostrRTCRoomPeerDisconnectListener) {
            this.addDisconnectionListener((NostrRTCRoomPeerDisconnectListener) listener);
        }
        if (listener instanceof NostrRTCRoomPeerMessageListener) {
            this.addMessageListener((NostrRTCRoomPeerMessageListener) listener);
        }
        if (listener instanceof NostrRTCRoomPeerDiscoveredListener) {
            this.addPeerDiscoveryListener((NostrRTCRoomPeerDiscoveredListener) listener);
        }
        return this;
    }

    public NostrRTCRoom removeListener(NostrRTCRoomListener listener) {
        if (listener instanceof NostrRTCPeerSocketAvailableListener) {
            this.onSocketAvailable.remove(listener);
        }
        if (listener instanceof NostrRTCRoomPeerDisconnectListener) {
            this.onDisconnectionListeners.remove(listener);
        }
        if (listener instanceof NostrRTCRoomPeerMessageListener) {
            this.onMessageListeners.remove(listener);
        }
        if (listener instanceof NostrRTCRoomPeerDiscoveredListener) {
            this.onPeerDiscoveredListeners.remove(listener);
        }
        return this;
    }

    private void loop() {
        this.executor.runLater(
                () -> {
                    try {
                        // try to connect to every announced peer
                        Collection<NostrRTCConnectSignal> announces = this.signaling.getAnnounces();
                        for (NostrRTCConnectSignal announce : announces) {
                            NostrRTCPeer remotePeer = announce.getPeer();
                            NostrPublicKey remotePubkey = remotePeer.getPubkey();

                            NostrRTCSocket socket = connections.get(remotePeer);

                            if (socket != null && (socket.isConnected() || socket.isPendingConnection())) continue;
                            synchronized(this){
                                socket = connections.get(remotePeer);   // make sure we have a fresh reference to the socket
                                                                        // it could have changed while we were waiting for the lock
                                if(socket != null && socket.isConnected()) continue;
                                if (socket != null && socket.isClosed()) {
                                    logger.fine("Dropping closed socket for peer: " + remotePubkey);
                                    connections.remove(remotePeer, socket);
                                    socket = null;
                                    
                                }

                                if (!shouldOfferConnection(remotePubkey)) continue;

                                logger.fine("Initiating connection to: " + remotePubkey);
                                if (socket == null) {
                                        socket = newSocket(remotePeer);
                                        socket.addListener(listener);
                                        connections.put(remotePeer, socket);
                                        for (NostrRTCPeerSocketAvailableListener listener : onSocketAvailable) {
                                            try {
                                                listener.onRoomPeerSocketAvailable(remotePeer, socket);
                                            } catch (Exception e) {
                                                logger.log(Level.WARNING, "Error notifying listener", e);
                                            }
                                        }
                                    
                                } else {
                                    socket.reset();
                                    
                                }


                                // send offer to remote peer
                                socket
                                    .listen()
                                    .then(offer -> {
                                        try {
                                            logger.fine("Sending offer to remote peer: " + remotePubkey);
                                            this.signaling.sendOffer(offer.getOfferString(), remotePubkey);
                                        } catch (Exception e) {
                                            // e.printStackTrace();
                                            logger.log(Level.WARNING, "Error sending offer", e);
                                        }
                                        return null;
                                    });
                            }
                        }
                    } catch (Exception e) {
                        logger.warning("Error in loop: " + e.getMessage());
                    }

                    this.loop();
                    return null;
                },
                settings.getRoomLoopInterval().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    // Check precedence of local peer over remote peer. Only one should initiate the connection to the other.
    // Doesn't really matter the approach as long as both peers are running the same logic. \
    // Here for simplicity we just compare the hex values of the pubkeys.
    private boolean shouldOfferConnection(NostrPublicKey pubkey) {
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

    public AsyncTask<Void> discover() {
        return this.signaling.start(false);
    }

    public AsyncTask<Void> start() {
        this.loop();
        return this.signaling.start(true);
    }

    /**
     * Disconnect a peer. The peer can reconnect immediately.
     * @param peer the peer to disconnect
     */
    public void kick(NostrRTCPeer peer) {
        List<NostrRTCSocket> sockets = removeSocketsForPeer(peer);
        if (sockets.isEmpty()) {
            logger.warning("No socket found for peer: " + peer);
            return;
        }
        logger.fine("Kicking peer: " + peer);
        for (NostrRTCSocket socket : sockets) {
            socket.close();
            for (NostrRTCRoomPeerDisconnectListener listener : onDisconnectionListeners) {
                try {
                    listener.onRoomPeerDisconnected(peer, socket);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying listener", e);
                }
            }
        }
    }

    /**
     * Disconnect all peers associated with a pubkey
     * @param peer the peer to disconnect
     */
    public void kick(NostrPublicKey peer) {
        List<NostrRTCSocket> sockets = removeSocketsForPubkey(peer);
        if (sockets.isEmpty()) {
            logger.warning("No socket found for peer: " + peer);
            return;
        }
        logger.fine("Kicking peer: " + peer);
        for (NostrRTCSocket socket : sockets) {
            socket.close();
            for (NostrRTCRoomPeerDisconnectListener listener : onDisconnectionListeners) {
                try {
                    listener.onRoomPeerDisconnected(socket.getRemotePeer(), socket);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying listener", e);
                }
            }
        }
    }

    protected void onRTCSocketClose(NostrRTCSocket socket) {
        // if the socket is closed remotely, we remove it from the list of connections
        // and notify the listeners
        NostrRTCPeer remotePeer = socket.getRemotePeer();
        if (remotePeer == null || remotePeer.getPubkey() == null) return;
        NostrRTCSocket current = connections.get(remotePeer);
        if (current != socket) return;
        connections.remove(remotePeer, socket);

        logger.fine("Closed peer: " + remotePeer);
        for (NostrRTCRoomPeerDisconnectListener listener : onDisconnectionListeners) {
            try {
                listener.onRoomPeerDisconnected(remotePeer, socket);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    /**
     * Ban a pubkey. Peers with the same pubkey will be disconnected and won't be able to reconnect until unbanned or the room is restarted.
     * @param peer the peer to ban
     */
    public void ban(NostrPublicKey peer) {
        if (!bannedPeers.contains(peer)) {
            logger.fine("Banning peer: " + peer);
            bannedPeers.add(peer);
        } else {
            logger.fine("Peer already banned: " + peer);
        }
        kick(peer);
    }

    /**
     * Unban a peer. The peer can reconnect immediately.
     * @param peer the peer to unban
     */
    public void unban(NostrPublicKey peer) {
        logger.fine("Unbanning peer: " + peer);
        bannedPeers.remove(peer);
    }

    protected void onAddAnnounce(NostrRTCConnectSignal announce) {
        for (NostrRTCRoomPeerDiscoveredListener listener : onPeerDiscoveredListeners) {
            try {
                listener.onRoomPeerDiscovered(
                    announce.getPeer(),
                    announce,
                    NostrRTCRoomPeerDiscoveredListener.NostrRTCRoomPeerDiscoveredState.ONLINE
                );
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    protected void onUpdateAnnounce(NostrRTCConnectSignal announce) {
        for (NostrRTCRoomPeerDiscoveredListener listener : onPeerDiscoveredListeners) {
            try {
                listener.onRoomPeerDiscovered(
                    announce.getPeer(),
                    announce,
                    NostrRTCRoomPeerDiscoveredListener.NostrRTCRoomPeerDiscoveredState.ONLINE
                );
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    protected void onRemoveAnnounce(NostrRTCConnectSignal announce, NostrRTCSignaling.Listener.RemoveReason reason) {
        // we use the announce as keep alive signaling. If the announce is not updated in a while
        // the peer is considered offline and the logical socket is closed.
        NostrRTCPeer remotePeer = announce.getPeer();
        logger.fine("Remove announce: " + announce + " reason: " + reason);
        for (NostrRTCRoomPeerDiscoveredListener listener : onPeerDiscoveredListeners) {
            try {
                listener.onRoomPeerDiscovered(
                    remotePeer,
                    announce,
                    NostrRTCRoomPeerDiscoveredListener.NostrRTCRoomPeerDiscoveredState.OFFLINE
                );
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }

        NostrRTCSocket socket = connections.get(remotePeer);
        if (socket != null) {
            socket.close();
            connections.remove(remotePeer, socket);
        }
    }

    /**
     * Get some info about the local peer
     * @return the local peer info
     */
    public NostrRTCPeer getLocalPeerInfo() {
        return this.localPeer;
    }

    protected void onReceiveOffer(NostrRTCOfferSignal offer) {
        synchronized(this){
            NostrRTCPeer remotePeer = offer.getPeer();
            // offer received from remote peer
            NostrRTCSocket existing = connections.get(remotePeer);
            NostrRTCSocket socket = null;
            if (existing != null && existing.isPendingConnection() && !shouldOfferConnection(remotePeer.getPubkey())) {
                // if there is already a connection initiated to this peer, forfeit it if the
                // remote has precedence over local.
                logger.fine(
                    "Forfeiting connection to peer: " +
                    remotePeer +
                    " because remote peer has precedence over local peer and is initiating the connection"
                );
                connections.remove(remotePeer, existing);
                existing.close();
            } else if (existing != null && existing.isConnected()) {
                logger.fine("Socket already exists for peer: " + remotePeer + ", ignoring offer");
                return;
            } else if (existing != null && !existing.isClosed()) {
                socket = existing;
                socket.reset();
            }

            logger.fine("Connecting to peer: " + remotePeer);
            if (socket == null) {
                socket = newSocket(remotePeer);
                socket.addListener(listener);
                connections.put(remotePeer, socket);
                for (NostrRTCPeerSocketAvailableListener listener : onSocketAvailable) {
                    try {
                        listener.onRoomPeerSocketAvailable(remotePeer, socket);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error notifying listener", e);
                    }
                }
            }

            // send answer to remote peer
            socket
                .connect(offer)
                .then(answer -> {
                    try {
                        logger.fine("Sending answer to remote peer: " + remotePeer);
                        if (answer != null) {
                            this.signaling.sendAnswer(answer.getSdp(), remotePeer.getPubkey());
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error sending answer", e);
                    }
                    return null;
                });
            }
    }

    protected void onReceiveAnswer(NostrRTCAnswerSignal answer) {
        synchronized(this){
            // answer received from remote peer
            NostrRTCPeer remotePeer = answer.getPeer();

            NostrRTCSocket socket = connections.get(remotePeer);
            if (socket != null && socket.isPendingConnection() && shouldOfferConnection(remotePeer.getPubkey())) {
                logger.fine("Received answer, finalizing connection to peer: " + remotePeer);
                // complete the connection
                socket
                    .connect(answer)
                    .then(ignored -> {
                        logger.fine("Connected to peer: " + remotePeer);
                        // connection completed
                        return null;
                    });
            } else {
                // if there is no pending connection, just ignore it
                logger.warning("No pending connection for peer: " + remotePeer);
            }
        }
    }
 

    protected void onReceiveCandidates(NostrRTCRouteSignal candidate) {
        logger.fine("Received ICE candidate: " + candidate);
        NostrRTCPeer remotePeer = candidate.getPeer();

        // receive remote candidate, add it to the socket
        NostrRTCSocket socket = connections.get(remotePeer);
        if (socket != null) {
            socket.mergeRemoteRTCIceCandidate(candidate);
        } else {
            logger.warning("No socket found for peer: " + remotePeer);
        }
    }

    protected void onRTCSocketLocalIceCandidate(
        NostrRTCSocket socket,
        Collection<RTCTransportIceCandidate> candidates,
        String turn
    ) {
        try {
            NostrRTCPeer remotePeer = socket.getRemotePeer();
            if (remotePeer == null) return;
            NostrPublicKey pubkey = remotePeer.getPubkey();
            if (pubkey == null) return;
            // receive local candidate, send it to the remote peer
            this.signaling.sendRoutes(candidates, turn, pubkey);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error sending local ICE candidate", e);
        }
    }

    /**
     * Send some data to a remote peer.
     * @param peer the remote peer to send the data to
     * @param bbf the data to send
     * @return an async task that will complete when the data is sent or fail if
     * the peer is not connected
     */
    public AsyncTask<Void> send(NostrRTCPeer peer, ByteBuffer bbf) {
        return send(NostrRTCSocket.DEFAULT_CHANNEL_NAME, peer, bbf);
    }

    public AsyncTask<Void> send(String channel, NostrRTCPeer peer, ByteBuffer bbf) {
        NostrRTCSocket socket = connections.get(peer);
        if (socket == null) {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
        NostrRTCChannel chan = socket.createChannel(channel);
        BlockingPacketQueue<ByteBuffer> q =
            pendingSends.computeIfAbsent(
                chan,
                ignored -> new BlockingPacketQueue<ByteBuffer>(
                    new BlockingPacketQueue.PacketHandler<ByteBuffer>() {
                        @Override
                        public AsyncTask<Boolean> handle(ByteBuffer packet) {
                            return chan.write(packet);
                        }

                        @Override
                        public boolean isReady() {
                            return chan.isWriteReady();
                        }
                    },
                    logger,
                    "Failed to send data to peer"
                )
            );
        return NGEUtils.getPlatform().wrapPromise((rs,rj)->{
            q.enqueue(bbf.duplicate(), rs, rj);
            drainQueue(chan);
        });      
    }

    public AsyncTask<Void> send(NostrRTCChannel chan, NostrRTCPeer peer, ByteBuffer bbf) {
        NostrRTCSocket socket = connections.get(peer);
        if (socket == null) {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
        BlockingPacketQueue<ByteBuffer> q =
            pendingSends.computeIfAbsent(
                chan,
                ignored -> new BlockingPacketQueue<ByteBuffer>(
                    new BlockingPacketQueue.PacketHandler<ByteBuffer>() {
                        @Override
                        public AsyncTask<Boolean> handle(ByteBuffer packet) {
                            return chan.write(packet);
                        }

                        @Override
                        public boolean isReady() {
                            return chan.isWriteReady();
                        }
                    },
                    logger,
                    "Failed to send data to peer"
                )
            );
        return NGEUtils.getPlatform().wrapPromise((rs,rj)->{
            q.enqueue(bbf.duplicate(), rs, rj);
            drainQueue(chan);
        });      
    }


    public NostrRTCChannel getChannel(NostrRTCPeer peer, String channel) {
        NostrRTCSocket socket = connections.get(peer);
        if (socket != null) {
            return socket.getChannel(channel);
        } else {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
    }

    public NostrRTCChannel createChannel(NostrRTCPeer peer, String channel) {
        NostrRTCSocket socket = connections.get(peer);
        if (socket != null) {
            return socket.createChannel(channel);
        } else {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
    }

    public NostrRTCChannel createChannel(
        NostrRTCPeer peer, 
        String channel, 
        boolean ordered,
        boolean reliable,
        @Nullable Integer maxRetransmits,
        @Nullable Duration maxPacketLifeTime
    ) {
        NostrRTCSocket socket = connections.get(peer);
        if (socket != null) {
            return socket.createChannel(channel, ordered, reliable, maxRetransmits, maxPacketLifeTime);
        } else {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
    }

    /**
     * Broadcast some data to all connected peers.
     * @param bbf the data to send
     * @return an async task that will complete when an attempt has been made to send the data
     * to all peers. If some peers fail to send the data, the task will still complete.
     */
    public AsyncTask<Void> broadcast(ByteBuffer bbf) {
        return broadcast(NostrRTCSocket.DEFAULT_CHANNEL_NAME, bbf);
    }

    public AsyncTask<Void> broadcast(String channel, ByteBuffer bbf) {
        ArrayList<AsyncTask<Void>> tasks = new ArrayList<>(connections.size());
        for (NostrRTCPeer peer : connections.keySet()) {
            tasks.add(send(channel, peer, bbf));
        }
        NGEPlatform platform = NGEUtils.getPlatform();
        return platform
            .awaitAllSettled(tasks)
            .then(r -> {
                return null;
            });
    }


    private List<NostrRTCSocket> removeSocketsForPubkey(NostrPublicKey peer) {
        List<NostrRTCSocket> removed = new ArrayList<>();
        for (Map.Entry<NostrRTCPeer, NostrRTCSocket> entry : new ArrayList<>(connections.entrySet())) {
            NostrRTCPeer key = entry.getKey();
            if (key == null || key.getPubkey() == null || !key.getPubkey().equals(peer)) continue;
            if (connections.remove(key, entry.getValue())) {
                removed.add(entry.getValue());
            }
        }
        return removed;
    }

    private List<NostrRTCSocket> removeSocketsForPeer(NostrRTCPeer peer) {
        List<NostrRTCSocket> removed = new ArrayList<>();
        for (Map.Entry<NostrRTCPeer, NostrRTCSocket> entry : new ArrayList<>(connections.entrySet())) {
            NostrRTCPeer key = entry.getKey();
            if (key == null || !key.equals(peer)) continue;
            if (connections.remove(key, entry.getValue())) {
                removed.add(entry.getValue());
            }
        }
        return removed;
    }
}
