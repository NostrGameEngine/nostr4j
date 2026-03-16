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

import jakarta.annotation.Nullable;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswerSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignal;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.RTCSettings;
import org.ngengine.platform.transport.RTCDataChannel;
import org.ngengine.platform.transport.RTCTransport;
import org.ngengine.platform.transport.RTCTransportIceCandidate;
import org.ngengine.platform.transport.RTCTransportListener;

/**
 * An RTC socket between two peers.
 * This class will try to establish a direct connection between the two peers, when
 * not possible it will fallback to a TURN server.
 *
 * Note:
 *      isConnected() reports RTC transport connectivity only.
 *      TURN failover is internal and surfaced through listeners transport switch events.
 *
 *      This is because, to avoid inefficiencies, the keep-alive mechanism is implemented only in the
 *      signaling protocol: when the signaling announce is stale, the socket should be closed using close().
 *      So keep in mind that you need to handle keep-alive youself, if you want to use this class by itself (without the signaling protocol).
 */
public final class NostrRTCSocket {

    public static final String DEFAULT_CHANNEL_NAME = "default";
    private static final Logger logger = Logger.getLogger(NostrRTCSocket.class.getName());
    private static final long RTC_CONNECT_TIMEOUT_MS = 1000;

    public static enum TransportPath {
        NONE,
        RTC,
        TURN,
    }

    private final List<NostrRTCSocketListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RTCTransportIceCandidate> localIceCandidates = new CopyOnWriteArrayList<>();

    private final RTCSettings settings;
    private final AsyncExecutor executor;
    private final NostrRTCLocalPeer localPeer;
    private final NostrKeyPair roomKeyPair;
    private final NostrTURNPool turnPool;
    private final Map<String, NostrRTCChannel> channels = new ConcurrentHashMap<>();

    @Nullable
    private final String turnServerUrl;

    private volatile RTCTransport transport;
    private final NostrRTCPeer remotePeer;
    private volatile boolean connected = false, stopped = false;
    private volatile AsyncTask<Void> delayedCandidateEmission;
    private volatile Instant pendingConnectionSince;
    private volatile AsyncTask<Void> rtcConnectDeadlineTask;
    private volatile TransportPath activeTransportPath = TransportPath.NONE;
    private volatile boolean turnFallbackAllowed = false;
    private volatile boolean forceTURN = false;

    private class NostrRTCListener implements RTCTransportListener {

        private final NostrRTCSocket socket;

        NostrRTCListener(NostrRTCSocket socket) {
            this.socket = socket;
        }

        @Override
        public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidateString) {
            logger.fine("Received local ICE candidate: " + candidateString);
            localIceCandidates.addIfAbsent(candidateString);
            emitCandidates();
        }

        @Override
        public void onRTCConnected() {
            logger.fine("Link established");
            connected = true;
            turnFallbackAllowed = false;
            pendingConnectionSince = null;
            cancelRtcConnectTimeout();
            switchActiveTransport(TransportPath.RTC, "rtc-connected");
            socket.resurrectChannels();
        }

        @Override
        public void onRTCDisconnected(String reason) {
            connected = false;
            pendingConnectionSince = null;
            logger.fine("RTC disconnected: " + reason);
            cancelRtcConnectTimeout();
            if (activeTransportPath == TransportPath.RTC) {
                switchActiveTransport(TransportPath.NONE, "rtc-disconnected");
            }
            for (NostrRTCSocketListener listener : listeners) {
                try {
                    listener.onRTCSocketTransportDegraded(socket, activeTransportPath, reason);
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Exception in listener", e);
                }
            }
            ensureTurnForDownChannels("rtc-disconnected:" + reason);
        }

        @Override
        public void onRTCBinaryMessage(RTCDataChannel chan, ByteBuffer bbf) {
            // for (NostrRTCSocketListener listener : listeners) {
            //     try {
            //         listener.onRTCSocketMessage(socket, chan,  bbf, false);
            //     } catch (Exception e) {
            //         logger.severe("Error emitting message: " + e.getMessage());
            //     }
            // }
            NostrRTCChannel logicalChannel = channels.get(chan.getName());
            // if (logicalChannel == null && isDefaultChannelName(chan.getName())) {
            //     logicalChannel =
            //         getOrCreateLogicalChannel(
            //             chan.getName(),
            //             chan.isOrdered(),
            //             chan.isReliable(),
            //             chan.getMaxRetransmits(),
            //             chan.getMaxPacketLifeTime()
            //         );
            // }
            if (logicalChannel != null) {
                logicalChannel.setChannel(chan);
                logicalChannel.onRTCSocketMessage(bbf);
            } else {
                logger.fine("Dropping binary for unknown logical channel: " + chan.getName());
            }
        }

        // @Override
        // public void onTurnPacket(SNostrRTCPeer peer, ByteBuffer data) {
        //     for (NostrRTCSocketListener listener : listeners) {
        //         try {
        //             listener.onRTCSocketMessage(socket, data, true);
        //         } catch (Exception e) {
        //             logger.severe("Error emitting message: " + e.getMessage());
        //         }
        //     }
        // }

        @Override
        public void onRTCChannelError(RTCDataChannel chan, Throwable e) {
            logger.severe("RTC Channel Error " + e);
            NostrRTCChannel logicalChannel = channels.get(chan.getName());
            if (logicalChannel != null) {
                logicalChannel.onRTCChannelError(e);
            }
            // for (NostrRTCSocketListener listener : listeners) {
            //     try {
            //         listener.onRTCChannelError(socket, chan, e);
            //     } catch (Exception ex) {
            //         logger.severe("Error emitting channel error: " + ex.getMessage());
            //     }
            // }
        }

        @Override
        public void onRTCChannelReady(RTCDataChannel channel) {
            NostrRTCChannel logicalChannel = channels.get(channel.getName());
            // if (logicalChannel == null && isDefaultChannelName(channel.getName())) {
            //     logicalChannel =
            //         getOrCreateLogicalChannel(
            //             channel.getName(),
            //             channel.isOrdered(),
            //             channel.isReliable(),
            //             channel.getMaxRetransmits(),
            //             channel.getMaxPacketLifeTime()
            //         );
            // }
            if (logicalChannel == null) {
                logger.fine("Ignoring ready for unknown logical channel: " + channel.getName());
                return;
            }
            logicalChannel.setChannel(channel);
            logger.fine("RTC Channel ready: " + channel.getName());
        }

        @Override
        public void onRTCChannelClosed(RTCDataChannel channel) {
            NostrRTCChannel logicalChannel = channels.get(channel.getName());
            if (logicalChannel != null) {
                logicalChannel.setChannel(null);
                // if(!logicalChannel.isClosed() && connected){
                //     logger.fine("RTC Channel closed: " + channel.getName() + ", but socket is still connected, resurrecting channel");
                //     socket.resurrectChannel(logicalChannel);
                //     return;
                // } else {
                //     logger.fine("RTC Channel closed: " + channel.getName());
                // }
            } else {
                logger.finer("RTC Channel closed: " + channel.getName() + ", but no logical channel found");
            }
            // channels.remove(channel.getName());
            // for (NostrRTCSocketListener listener : listeners) {
            //     try {
            //         listener.onRTCChannelClosed(channel);
            //     } catch (Exception e) {
            //         logger.severe("Error emitting channel closed: " + e.getMessage());
            //     }
            // }
        }

        @Override
        public void onRTCBufferedAmountLow(RTCDataChannel channel) {
            NostrRTCChannel logicalChannel = channels.get(channel.getName());
            if (logicalChannel != null) {
                logicalChannel.onRTCBufferedAmountLow();
            }
            logger.fine("RTC Channel buffered amount low: " + channel.getName());
            // for (NostrRTCSocketListener listener : listeners) {
            //     try {
            //         listener.onRTCBufferedAmountLow(channel);
            //     } catch (Exception e) {
            //         logger.severe("Error emitting buffered amount low: " + e.getMessage());
            //     }
            // }
        }
    }

    private final NostrRTCListener rtcListener = new NostrRTCListener(this);

    NostrRTCSocket(
        AsyncExecutor executor,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        NostrRTCLocalPeer localPeer,
        RTCSettings settings,
        @Nullable String turnServerUrl,
        NostrTURNPool turnPool
    ) {
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local Peer cannot be null");
        this.roomKeyPair = Objects.requireNonNull(roomKeyPair, "Room Key Pair cannot be null");
        this.remotePeer = Objects.requireNonNull(remotePeer, "Remote Peer cannot be null");
        this.turnPool = turnPool;
        this.turnServerUrl = turnServerUrl;
    }

    // private NostrRTCChannel getOrCreateLogicalChannel(
    //     String name,
    //     boolean ordered,
    //     boolean reliable,
    //     @Nullable Integer maxRetransmits,
    //     @Nullable Duration maxPacketLifeTime
    // ) {
    //     NostrRTCChannel channel = channels.computeIfAbsent(
    //         name,
    //         n -> {
    //             NostrRTCChannel c = new NostrRTCChannel(
    //                 name,
    //                 this,
    //                 ordered,
    //                 reliable,
    //                 maxRetransmits != null ? maxRetransmits : Integer.valueOf(0),
    //                 maxPacketLifeTime
    //             );
    //             for(NostrRTCSocketListener listener : listeners) {
    //                 try {
    //                     listener.onRTCChannel(c);
    //                 } catch (Exception e) {
    //                     logger.severe("Error emitting channel: " + e.getMessage());
    //                 }
    //             }
    //             return c;
    //         }
    //     );

    //     return channel;
    // }

    private void switchActiveTransport(TransportPath next, String reason) {
        TransportPath previous = this.activeTransportPath;
        if (previous == next) return;
        this.activeTransportPath = next;
        for (NostrRTCSocketListener listener : listeners) {
            try {
                listener.onRTCSocketTransportSwitch(this, previous, next, reason);
            } catch (Throwable e) {
                logger.severe("Error emitting transport switch: " + e.getMessage());
            }
        }
    }

    private void cancelRtcConnectTimeout() {
        AsyncTask<Void> task = rtcConnectDeadlineTask;
        if (task != null) {
            task.cancel();
            rtcConnectDeadlineTask = null;
        }
    }

    private void scheduleRtcConnectTimeout(String reason) {
        cancelRtcConnectTimeout();
        if (stopped) return;
        rtcConnectDeadlineTask =
            executor.runLater(
                () -> {
                    rtcConnectDeadlineTask = null;
                    if (stopped || connected || transport == null) return null;
                    for (NostrRTCSocketListener listener : listeners) {
                        try {
                            listener.onRTCSocketTransportDegraded(this, activeTransportPath, "rtc-timeout");
                        } catch (Throwable e) {
                            logger.log(Level.SEVERE, "Exception in listener", e);
                        }
                    }
                    ensureTurnForDownChannels("rtc-timeout:" + reason);
                    return null;
                },
                RTC_CONNECT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            );
    }

    private void ensureTurnForDownChannels(String reason) {
        turnFallbackAllowed = true;
        for (NostrRTCChannel channel : channels.values()) {
            if (!channel.isClosed() && !channel.isConnected()) {
                channel.setChannel(null);
            }
        }
    }

    @Nullable
    String resolveReceiveTurnUrl() {
        String localTurnServer = localPeer.getTurnServer();
        if (localTurnServer != null && !localTurnServer.isEmpty()) {
            return localTurnServer;
        }
        if (turnServerUrl != null && !turnServerUrl.isEmpty()) {
            return turnServerUrl;
        }
        return null;
    }

    @Nullable
    String resolveSendTurnUrl() {
        NostrRTCPeer currentRemotePeer = remotePeer;
        if (currentRemotePeer != null) {
            String remoteTurnServer = currentRemotePeer.getTurnServer();
            if (remoteTurnServer != null && !remoteTurnServer.isEmpty()) {
                return remoteTurnServer;
            }
        }
        if (turnServerUrl != null && !turnServerUrl.isEmpty()) {
            return turnServerUrl;
        }
        return null;
    }

    void setForceTURN(boolean forceTURN) {
        this.forceTURN = forceTURN;
    }

    boolean isForceTURN() {
        return forceTURN;
    }

    private void resurrectChannel(NostrRTCChannel channel) {
        if (transport == null || !transport.isConnected()) return;
        if (!channel.isConnected() && !channel.isClosed() && !channel.isResurrecting()) {
            if (!shouldCreateDataChannelLocally()) {
                return;
            }
            channel.setResurrecting(true);
            // logger.fine("Resurrecting channel " + channel.getName());
            transport
                .createDataChannel(
                    channel.getName(),
                    localPeer.getProtocolId(),
                    channel.isOrdered(),
                    channel.isReliable(),
                    channel.getMaxRetransmits(),
                    channel.getMaxPacketLifeTime()
                )
                .then(newChannel -> {
                    channel.setResurrecting(false);
                    channel.setChannel(newChannel);
                    logger.fine("Channel " + channel.getName() + " resurrected");
                    return null;
                })
                .catchException(e -> {
                    channel.setResurrecting(false);
                    logger.severe("Error resurrecting channel " + channel.getName() + ": " + e.getMessage());
                });
        }
    }

    private void resurrectChannels() {
        executor.runLater(
            () -> {
                if (stopped) return null;
                if (connected) {
                    for (NostrRTCChannel channel : channels.values()) {
                        resurrectChannel(channel);
                    }
                }
                this.resurrectChannels();
                return null;
            },
            100,
            TimeUnit.MILLISECONDS
        );
    }

    private boolean shouldCreateDataChannelLocally() {
        NostrRTCPeer remote = remotePeer;
        if (remote == null || remote.getPubkey() == null || localPeer.getPubkey() == null) {
            throw new IllegalStateException("Cannot determine channel initiator, missing peer information");
        }
        return localPeer.getPubkey().asHex().compareTo(remote.getPubkey().asHex()) < 0;
    }

    private static String normalizeChannelName(String name) {
        String nativeName = DEFAULT_CHANNEL_NAME.equals(name) ? RTCTransport.DEFAULT_CHANNEL : name;
        if (nativeName == null || nativeName.isEmpty()) {
            nativeName = DEFAULT_CHANNEL_NAME;
        }
        return nativeName;
    }

    private static boolean isDefaultChannelName(String channelName) {
        if (channelName == null) {
            return false;
        }
        return DEFAULT_CHANNEL_NAME.equals(channelName) || RTCTransport.DEFAULT_CHANNEL.equals(channelName);
    }

    void emitChannelReady(NostrRTCChannel channel) {
        if (channel == null || channel.isClosed()) return;
        for (NostrRTCSocketListener listener : listeners) {
            try {
                listener.onRTCChannelReady(channel);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    /**
     * Get the local peer.
     * @return The local peer.
     */
    public NostrRTCLocalPeer getLocalPeer() {
        return localPeer;
    }

    /**
     * Get the remote peer if connected, otherwise null.
     * @return The remote peer or null.
     */
    public NostrRTCPeer getRemotePeer() {
        return remotePeer;
    }

    NostrKeyPair getRoomKeyPair() {
        return roomKeyPair;
    }

    NostrTURNPool getTurnPool() {
        return turnPool;
    }

    boolean isTurnFallbackAllowed() {
        return turnFallbackAllowed;
    }

    /**
     * Return true if the connection is established.
     * @return True if the connection is established.
     */
    boolean isConnected() {
        return connected;
    }

    public boolean isClosed() {
        return stopped;
    }

    /**
     * Close the socket.
     */
    void close() {
        stopped = true;
        logger.fine("Closing RTC Socket");

        for (NostrRTCChannel channel : channels.values()) {
            channel.close();
        }

        reset();

        channels.clear();

        // if (this.transport != null) try {
        //     this.transport.close();
        // } catch (Exception e) {
        //     logger.severe("Error closing transport: " + e.getMessage());
        // }
        // TODO implement turn
        // if (this.turn != null) try {
        //     this.turn.close();
        // } catch (Exception e) {
        //     logger.severe("Error closing TURN: " + e.getMessage());
        // }
        // if (delayedCandidateEmission != null) {
        //     delayedCandidateEmission.cancel();
        // }
        // delayedCandidateEmission = null;
        // pendingConnectionSince = null;
        // localIceCandidates.clear();
        for (NostrRTCSocketListener listener : listeners) {
            try {
                listener.onRTCSocketClose(this);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
        listeners.clear();
        connected = false;
        switchActiveTransport(TransportPath.NONE, "socket-closed");
    }

    void reset() {
        logger.fine("Resetting RTC Socket");
        connected = false;
        turnFallbackAllowed = false;
        cancelRtcConnectTimeout();
        for (NostrRTCChannel channel : channels.values()) {
            channel.setChannel(null);
        }
        if (this.transport != null) try {
            this.transport.close();
        } catch (Exception e) {
            logger.severe("Error closing transport: " + e.getMessage());
        }
        this.transport = null;
        if (delayedCandidateEmission != null) {
            delayedCandidateEmission.cancel();
        }
        delayedCandidateEmission = null;
        pendingConnectionSince = null;
        localIceCandidates.clear();
        if (activeTransportPath == TransportPath.RTC) {
            switchActiveTransport(TransportPath.NONE, "rtc-reset");
        }
    }

    void addListener(NostrRTCSocketListener listener) {
        listeners.add(listener);
    }

    void removeListener(NostrRTCSocketListener listener) {
        listeners.remove(listener);
    }

    // internal, emit all candidates after a delay
    private void emitCandidates() {
        if (delayedCandidateEmission != null) {
            delayedCandidateEmission.cancel();
        }

        delayedCandidateEmission =
            this.executor.runLater(
                    () -> {
                        logger.fine("Emitting ICE candidates " + localIceCandidates);

                        for (NostrRTCSocketListener listener : listeners) {
                            try {
                                listener.onRTCSocketRouteUpdate(
                                    this,
                                    new ArrayList<RTCTransportIceCandidate>(localIceCandidates),
                                    localPeer.getTurnServer()
                                );
                            } catch (Throwable e) {
                                logger.log(Level.SEVERE, "Exception in listener", e);
                            }
                        }
                        return null;
                    },
                    settings.getDelayedCandidatesInterval().toMillis(),
                    TimeUnit.MILLISECONDS
                );
    }

    /**
     * Listen for incoming RTC connections.
     * @return An async task that resolves with the offer string.
     * @throws IllegalStateException If the socket is already connected.
     */
    AsyncTask<NostrRTCOfferSignal> listen() {
        try {
            if (this.transport != null) throw new IllegalStateException("Already connected");

            logger.fine("Listening for RTC connections on connection ID: " + localPeer.getSessionId());
            this.pendingConnectionSince = Instant.now();
            scheduleRtcConnectTimeout("listen");
            // useTURN(false);

            NGEPlatform platform = NGEUtils.getPlatform();
            logger.fine("Creating RTC transport for connection ID: " + localPeer.getSessionId());

            this.transport = platform.newRTCTransport(settings, localPeer.getSessionId(), localPeer.getStunServers());
            this.transport.addListener(rtcListener);

            logger.fine("Initiating RTC channel for connection ID: " + localPeer.getSessionId());

            return this.transport.listen()
                .then(offerString -> {
                    logger.fine(
                        "Use offer string: " + offerString + " to connect with connection ID: " + localPeer.getSessionId()
                    );
                    NostrRTCOfferSignal offer = new NostrRTCOfferSignal(
                        localPeer.getSigner(),
                        roomKeyPair,
                        localPeer,
                        offerString
                    );
                    logger.fine("Ready to send offer " + offer + " to connection ID: " + localPeer.getSessionId());

                    return offer;
                })
                .catchException(ex -> {
                    logger.severe("Error while listening for RTC connections: " + ex.getMessage());
                    throw new IllegalStateException("Error while listening for RTC connections", ex);
                });
        } catch (Exception e) {
            logger.severe("Error while listening for RTC connections: " + e.getMessage());
            throw new IllegalStateException("Error while listening for RTC connections", e);
        }
    }

    /**
     * Connect to a remote peer.
     * @param offerOrAnswer The offer or answer to connect to.
     * @return An async tasks that resolves after the connection is established with an
     * answer string if the argument is an offer or null if the argument is an answer.
     * @throws IllegalStateException If the socket is already connected or cannot be connected
     * @throws IllegalArgumentException If the argument is not an offer or answer.
     */
    AsyncTask<NostrRTCAnswerSignal> connect(NostrRTCSignal offerOrAnswer) {
        Objects.requireNonNull(offerOrAnswer);
        logger.fine("Connecting to RTC socket " + offerOrAnswer);
        this.pendingConnectionSince = Instant.now();
        scheduleRtcConnectTimeout("connect");
        // useTURN(false);

        NGEPlatform platform = NGEUtils.getPlatform();

        String connectString;
        if (offerOrAnswer instanceof NostrRTCOfferSignal) {
            if (this.transport != null) throw new IllegalStateException("Already connected");
            this.transport = platform.newRTCTransport(settings, localPeer.getSessionId(), localPeer.getStunServers());
            this.transport.addListener(rtcListener);
            logger.fine("Use offer to connect");
            this.remotePeer.merge(((NostrRTCOfferSignal) offerOrAnswer).getPeer());
            // this.remotePeer =
            //     Objects.requireNonNull(((NostrRTCOfferSignal) offerOrAnswer).getPeer(), "Remote Peer cannot be null");
            emitCandidates();
            connectString = ((NostrRTCOfferSignal) offerOrAnswer).getOfferString();
        } else if (offerOrAnswer instanceof NostrRTCAnswerSignal) {
            // logger.fine("Use answer to connect");
            if (this.transport == null) throw new IllegalStateException("Not connected");
            this.remotePeer.merge(((NostrRTCAnswerSignal) offerOrAnswer).getPeer());
            emitCandidates();
            connectString = ((NostrRTCAnswerSignal) offerOrAnswer).getSdp();
        } else {
            throw new IllegalArgumentException("Invalid RTC signal type");
        }

        return this.transport.connect(connectString)
            .then(answerString -> {
                if (answerString == null) {
                    logger.fine("Connected to RTC socket");
                    return null;
                }
                logger.fine("Connected to RTC socket, received answer " + answerString);
                NostrRTCAnswerSignal answer = new NostrRTCAnswerSignal(
                    localPeer.getSigner(),
                    roomKeyPair,
                    localPeer,
                    answerString
                );
                return answer;
            });
    }

    /**
     * Merge remote ICE candidates with the already
     * tracked candidates.
     * @param candidate The remote ICE candidates.
     */
    void mergeRemoteRTCIceCandidate(NostrRTCRouteSignal candidate) {
        Objects.requireNonNull(candidate);
        NostrRTCPeer currentRemotePeer = this.remotePeer;
        if (currentRemotePeer != null) {
            candidate.updatePeer(currentRemotePeer);
        }
        if (this.transport == null) return;
        this.transport.addRemoteIceCandidates(candidate.getCandidates());
    }

    NostrRTCChannel getChannel(String name) {
        String nativeName = normalizeChannelName(name);
        NostrRTCChannel channel = channels.get(nativeName);
        if (channel == null) {
            return null;
        }
        channel.activateFallbackIfNeeded();
        resurrectChannel(channel);
        return channel;
    }

    final NostrRTCChannel createChannel(String name) {
        return createChannel(name, true, true, null, null);
    }

    NostrRTCChannel createChannel(
        String name,
        boolean ordered,
        boolean reliable,
        @Nullable Integer maxRetransmits,
        @Nullable Duration maxPacketLifeTime
    ) {
        String nativeName = normalizeChannelName(name);
        final String channelName = nativeName;
        Integer normalizedMaxRetransmits = maxRetransmits != null ? maxRetransmits : Integer.valueOf(0);
        NostrRTCChannel chan =
            this.channels.computeIfAbsent(
                    channelName,
                    n -> {
                        NostrRTCChannel nchan = new NostrRTCChannel(
                            channelName,
                            this,
                            ordered,
                            reliable,
                            normalizedMaxRetransmits,
                            maxPacketLifeTime
                        );
                        for (NostrRTCSocketListener listener : listeners) {
                            try {
                                listener.onRTCChannel(nchan);
                            } catch (Throwable e) {
                                logger.log(Level.SEVERE, "Exception in listener", e);
                            }
                        }
                        // emitChannelReady(nchan);
                        return nchan;
                    }
                );
        RTCTransport currentTransport = this.transport;
        if (currentTransport != null) {
            RTCDataChannel existingChannel = currentTransport.getDataChannel(channelName);
            if (existingChannel != null) {
                chan.setChannel(existingChannel);
            }
        }
        chan.activateFallbackIfNeeded();
        return chan;
        // NostrRTCChannel channel = channels.computeIfAbsent(nativeName, n -> new NostrRTCChannel(
        //     nativeName, this, ordered, reliable, maxRetransmits, maxPacketLifeTime
        // ));
        // this.transport.createDataChannel(nativeName, localPeer.getProtocolId(), ordered, reliable,
        //     maxRetransmits != null ? maxRetransmits.intValue() : 0, maxPacketLifeTime).then(e->{

        //         channel.setChannel(e);
        //         return channel;
        // });
        // return channel;
    }

    /**
     * Send some data to the remote peer.
     * @param bbf The data to send (use Direct Buffers for performance).
     * @throws IllegalStateException If the socket is not connected.
     * @throws IllegalArgumentException If the data is null.
     * @return An async task that resolves when the data is sent.
     * @deprecated use getChannel(DEFAULT_CHANNEL_NAME).write(ByteBuffer)
     */
    @Deprecated
    AsyncTask<Boolean> write(ByteBuffer bbf) {
        // if (this.useTURN) {
        //     assert dbg(() -> {
        //         logger.finest("Send message with turn");
        //     });
        //     return this.turn.write(bbf);
        // } else {
        // assert dbg(() -> {
        //     logger.finest("Send message p2p");
        // });
        // return this.transport.write(bbf);
        // }
        return getChannel(DEFAULT_CHANNEL_NAME).write(bbf);
    }

    boolean isPendingConnection() {
        if (pendingConnectionSince == null) return false;
        if (connected || stopped) return false;
        return pendingConnectionSince.plus(settings.getPeerExpiration()).isAfter(Instant.now());
    }
}
