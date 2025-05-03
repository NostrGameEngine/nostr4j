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

import static org.ngengine.nostr4j.utils.NostrUtils.dbg;

import java.io.Closeable;
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
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerConnectedListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerDisconnectListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerDiscoveredListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerMessageListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnnounce;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCIceCandidate;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOffer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.nostr4j.rtc.turn.NostrTURNSettings;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrRTCRoom implements Closeable {

    private static final Logger logger = Logger.getLogger(NostrRTCRoom.class.getName());

    private static class PendingConnection {

        Instant createdAt;
        NostrRTCSocket socket;
    }

    private final Map<NostrPublicKey, PendingConnection> pendingInitiatedConnections = new ConcurrentHashMap<>();
    private final Map<NostrPublicKey, NostrRTCSocket> connections = new ConcurrentHashMap<>();
    private final Collection<NostrPublicKey> bannedPeers = new CopyOnWriteArrayList<>();

    private final List<NostrRTCRoomPeerConnectedListener> onConnectionListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerDisconnectListener> onDisconnectionListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerMessageListener> onMessageListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerDiscoveredListener> onPeerDiscoveredListeners = new CopyOnWriteArrayList<>();

    private final NostrRTCLocalPeer localPeer;
    private final NostrRTCSignaling signaling;
    private final NostrRTCSettings settings;
    private final NostrTURNSettings turnSettings;
    private final NostrExecutor executor;
    private final NostrKeyPair roomKeyPair;

    private static interface Listener extends NostrRTCSignaling.Listener, NostrRTCSocketListener {}

    private final Listener listener = new Listener() {
        @Override
        public void onAddAnnounce(NostrRTCAnnounce announce) {
            NostrRTCRoom.this.onAddAnnounce(announce);
        }

        @Override
        public void onUpdateAnnounce(NostrRTCAnnounce announce) {
            NostrRTCRoom.this.onUpdateAnnounce(announce);
        }

        @Override
        public void onRTCSocketClose(NostrRTCSocket socket) {
            NostrRTCRoom.this.onRTCSocketClose(socket);
        }

        @Override
        public void onReceiveOffer(NostrRTCOffer offer) {
            NostrRTCRoom.this.onReceiveOffer(offer);
        }

        @Override
        public void onReceiveAnswer(NostrRTCAnswer answer) {
            NostrRTCRoom.this.onReceiveAnswer(answer);
        }

        @Override
        public void onReceiveCandidates(NostrRTCIceCandidate candidate) {
            NostrRTCRoom.this.onReceiveCandidates(candidate);
        }

        @Override
        public void onRTCSocketMessage(NostrRTCSocket socket, ByteBuffer bbf, boolean turn) {
            NostrRTCRoom.this.onRTCSocketMessage(socket, bbf, turn);
        }

        @Override
        public void onRTCSocketLocalIceCandidate(NostrRTCSocket socket, NostrRTCIceCandidate candidate) {
            NostrRTCRoom.this.onRTCSocketLocalIceCandidate(socket, candidate);
        }

        @Override
        public void onRemoveAnnounce(NostrRTCAnnounce announce, RemoveReason reason) {
            NostrRTCRoom.this.onRemoveAnnounce(announce, reason);
        }
    };

    public NostrRTCRoom(
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
        this.signaling =
            new NostrRTCSignaling(
                settings,
                localPeer,
                roomKeyPair,
                Objects.requireNonNull(signalingPool, "Signaling pool cannot be null")
            );
        this.signaling.addListener(listener);
        Platform platform = NostrUtils.getPlatform();
        this.executor = platform.newPoolExecutor();
    }

    @Override
    public void close() {
        // close everything
        for (NostrRTCSocket socket : connections.values()) {
            socket.close();
        }
        for (PendingConnection conn : pendingInitiatedConnections.values()) {
            conn.socket.close();
        }
        this.signaling.close();
        this.executor.close();
    }

    public NostrRTCRoom addMessageListener(NostrRTCRoomPeerMessageListener listener) {
        this.onMessageListeners.add(listener);
        return this;
    }

    public NostrRTCRoom addConnectionListener(NostrRTCRoomPeerConnectedListener listener) {
        this.onConnectionListeners.add(listener);
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

    public NostrRTCRoom addListener(NostrRTCRoomListener listener) {
        if (listener instanceof NostrRTCRoomPeerConnectedListener) {
            this.addConnectionListener((NostrRTCRoomPeerConnectedListener) listener);
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
        if (listener instanceof NostrRTCRoomPeerConnectedListener) {
            this.onConnectionListeners.remove(listener);
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

                            conn.socket.addListener(listener);

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
    public void kick(NostrPublicKey peer) {
        // forcefully disconnect a peer
        NostrRTCSocket socket = connections.remove(peer);
        if (socket != null) {
            logger.fine("Kicking peer: " + peer);
            socket.close();
            for (NostrRTCRoomPeerDisconnectListener listener : onDisconnectionListeners) {
                try {
                    listener.onRoomPeerDisconnected(socket.getRemotePeer().getPubkey(), socket);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying listener", e);
                }
            }
        } else {
            logger.warning("No socket found for peer: " + peer);
        }
    }

    protected void onRTCSocketClose(NostrRTCSocket socket) {
        // if the socket is closed remotely, we remove it from the list of connections
        // and notify the listeners
        NostrRTCSocket c = connections.remove(socket.getRemotePeer().getPubkey());
        if (c != null) {
            logger.fine("Closed peer: " + socket.getRemotePeer().getPubkey());
            for (NostrRTCRoomPeerDisconnectListener listener : onDisconnectionListeners) {
                try {
                    listener.onRoomPeerDisconnected(socket.getRemotePeer().getPubkey(), socket);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying listener", e);
                }
            }
        }
    }

    /**
     * Ban a peer. The peer cannot reconnect until unbanned.
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

    protected void onAddAnnounce(NostrRTCAnnounce announce) {
        for (NostrRTCRoomPeerDiscoveredListener listener : onPeerDiscoveredListeners) {
            try {
                listener.onRoomPeerDiscovered(
                    announce.getPubkey(),
                    announce,
                    NostrRTCRoomPeerDiscoveredListener.NostrRTCRoomPeerDiscoveredState.ONLINE
                );
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    protected void onUpdateAnnounce(NostrRTCAnnounce announce) {
        for (NostrRTCRoomPeerDiscoveredListener listener : onPeerDiscoveredListeners) {
            try {
                listener.onRoomPeerDiscovered(
                    announce.getPubkey(),
                    announce,
                    NostrRTCRoomPeerDiscoveredListener.NostrRTCRoomPeerDiscoveredState.OFFLINE
                );
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    protected void onRemoveAnnounce(NostrRTCAnnounce announce, NostrRTCSignaling.Listener.RemoveReason reason) {
        // we use the announce as keep alive signaling. If the announce is not updated in a while
        // the peer will disconnect automatically.
        NostrPublicKey remotePubkey = announce.getPubkey();
        logger.fine("Remove announce: " + announce + " reason: " + reason);
        // close connections
        NostrRTCSocket socket = connections.remove(remotePubkey);
        if (socket != null) {
            socket.close();
            for (NostrRTCRoomPeerDisconnectListener listener : onDisconnectionListeners) {
                try {
                    listener.onRoomPeerDisconnected(socket.getRemotePeer().getPubkey(), socket);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying listener", e);
                }
            }
        }

        // even if it is pending!
        PendingConnection conn = pendingInitiatedConnections.remove(remotePubkey);
        if (conn != null) {
            conn.socket.close();
        }
    }

    /**
     * Get some info about the remote peer.
     * @param pubkey the pubkey of the remote peer
     * @return the remote peer info
     */
    public NostrRTCPeer getRemotePeerInfo(NostrPublicKey pubkey) {
        NostrRTCPeer peer = connections.get(pubkey).getRemotePeer();
        if (peer != null) {
            return peer;
        }
        return null;
    }

    /**
     * Get some info about the local peer
     * @return the local peer info
     */
    public NostrRTCPeer getLocalPeerInfo() {
        return this.localPeer;
    }

    protected void onReceiveOffer(NostrRTCOffer offer) {
        NostrPublicKey remotePubkey = offer.getPeerInfo().getPubkey();
        // offer received from remote peer
        PendingConnection conn = pendingInitiatedConnections.get(remotePubkey);
        if (conn != null) {
            // if there is already a connection initiated to this peer, forfeit it if the
            // remote has precedence over local.
            pendingInitiatedConnections.remove(remotePubkey);
            logger.fine(
                "Forfeiting connection to peer: " +
                remotePubkey +
                " because remote peer has precedence over local peer and is initiating the connection"
            );
            conn.socket.close();
        }

        logger.fine("Connecting to peer: " + remotePubkey);
        NostrRTCSocket socket = new NostrRTCSocket(
            executor,
            localPeer,
            roomKeyPair.getPublicKey().asHex(),
            settings,
            turnSettings
        );
        socket.addListener(listener);

        // send answer to remote peer
        socket
            .connect(offer)
            .then(answer -> {
                try {
                    logger.fine("Sending answer to remote peer: " + remotePubkey);
                    this.signaling.sendAnswer(answer, remotePubkey);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error sending answer", e);
                }
                return null;
            });
        connections.put(remotePubkey, socket);
        for (NostrRTCRoomPeerConnectedListener listener : onConnectionListeners) {
            try {
                listener.onRoomPeerConnected(socket.getRemotePeer().getPubkey(), socket);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    protected void onReceiveAnswer(NostrRTCAnswer answer) {
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

            for (NostrRTCRoomPeerConnectedListener listener : onConnectionListeners) {
                try {
                    listener.onRoomPeerConnected(remotePubkey, conn.socket);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error notifying listener", e);
                }
            }
        } else {
            // if there is no pending connection, just ignore it
            logger.warning("No pending connection for peer: " + remotePubkey);
        }
    }

    protected void onReceiveCandidates(NostrRTCIceCandidate candidate) {
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

    protected void onRTCSocketLocalIceCandidate(NostrRTCSocket socket, NostrRTCIceCandidate candidate) {
        try {
            NostrRTCPeer remotePeer = socket.getRemotePeer();
            if (remotePeer == null) return;
            NostrPublicKey pubkey = remotePeer.getPubkey();
            if (pubkey == null) return;
            // receive local candidate, send it to the remote peer
            this.signaling.sendCandidates(candidate, pubkey);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error sending local ICE candidate", e);
        }
    }

    protected void onRTCSocketMessage(NostrRTCSocket socket, ByteBuffer bbf, boolean turn) {
        // receive message from remote peer
        for (NostrRTCRoomPeerMessageListener listener : onMessageListeners) {
            try {
                listener.onRoomPeerMessage(socket.getRemotePeer().getPubkey(), socket, bbf, turn);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }

        byte bbfArray[] = new byte[bbf.remaining()];
        bbf.get(bbfArray);
        bbf.flip();
        assert dbg(() -> {
            logger.finest(
                "Received message from peer: " +
                socket.getRemotePeer().getPubkey() +
                " : " +
                new String(bbfArray) +
                " turn: " +
                turn
            );
        });
    }

    /**
     * Send some data to a remote peer.
     * @param peer the remote peer to send the data to
     * @param bbf the data to send
     * @return an async task that will complete when the data is sent or fail if
     * the peer is not connected
     */
    public AsyncTask<Void> send(NostrPublicKey peer, ByteBuffer bbf) {
        NostrRTCSocket socket = connections.get(peer);
        if (socket != null) {
            return socket.write(bbf);
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
        ArrayList<AsyncTask<Void>> tasks = new ArrayList<>(connections.size());
        for (NostrRTCSocket socket : connections.values()) {
            tasks.add(socket.write(bbf));
        }
        Platform platform = NostrUtils.getPlatform();
        return platform
            .awaitAllSettled(tasks)
            .then(r -> {
                return null;
            });
    }
}
