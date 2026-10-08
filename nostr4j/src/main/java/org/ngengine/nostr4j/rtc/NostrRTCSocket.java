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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.routing.InternalRoutedTransport;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
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
import org.ngengine.platform.SafeFlag;
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
 *      isRTCConnected() reports RTC transport connectivity only.
 *      TURN failover is internal and surfaced through listeners transport switch events.
 *
 *      This is because, to avoid inefficiencies, the keep-alive mechanism is implemented only in the
 *      signaling protocol: when the signaling announce is stale, the socket should be closed using close().
 *      So keep in mind that you need to handle keep-alive youself, if you want to use this class by itself (without the signaling protocol).
 */
public final class NostrRTCSocket {

    public static final String DEFAULT_CHANNEL_NAME = "default";
    private static final Logger logger = Logger.getLogger(NostrRTCSocket.class.getName());
    private static final long RTC_UPGRADE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    public static enum TransportPath {
        NONE,
        RTC,
        TURN,
    }

    private final List<NostrRTCSocketListener> listeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCSocketListener> internalListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RTCTransportIceCandidate> localIceCandidates = new CopyOnWriteArrayList<>();

    private final RTCSettings settings;
    private final AsyncExecutor executor;
    private final NostrRTCLocalPeer localPeer;
    private final NostrKeyPair roomKeyPair;
    private final NostrTURNPool turnPool;
    private final Map<String, NostrRTCChannel> channels = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_CHANNEL_MESSAGES = 64;
    private static final int MAX_PENDING_CHANNEL_BYTES = NostrRTCChannel.MAX_REASSEMBLY_BYTES;
    // Guarded by this socket. Registration and delivery callbacks never run under its monitor.
    private final Map<NostrRTCChannel, PendingChannelRegistration> channelRegistrations = new HashMap<>();

    private static final class PendingChannelRegistration {

        private final ArrayDeque<PendingChannelMessage> messages = new ArrayDeque<>();
        private int pendingBytes;
    }

    private static final class PendingChannelMessage {

        private final BoundRTCListener source;
        private final RTCDataChannel nativeChannel;
        private final ByteBuffer data;

        private PendingChannelMessage(BoundRTCListener source, RTCDataChannel nativeChannel, ByteBuffer data) {
            this.source = source;
            this.nativeChannel = nativeChannel;
            this.data = data;
        }
    }

    private volatile RTCTransport transport;
    private BoundRTCListener currentRtcListener;
    private long rtcTransportGeneration;
    private long channelResurrectionGeneration = Long.MIN_VALUE;
    private final List<RTCTransportIceCandidate> pendingRemoteIceCandidates = new ArrayList<>();
    private static final int MAX_PENDING_REMOTE_ICE_CANDIDATES = 128;
    private long transportEventGeneration;
    private final NostrRTCPeer remotePeer;
    private volatile boolean connected = false, stopped = false;
    private volatile AsyncTask<Void> delayedCandidateEmission;
    private long candidateEmissionGeneration;
    private volatile Instant pendingConnectionSince;
    private volatile AsyncTask<Void> rtcConnectDeadlineTask;
    private long rtcConnectAttempt;
    private volatile TransportPath activeTransportPath = TransportPath.NONE;
    private volatile boolean turnFallbackAllowed = false;
    private final SafeFlag forceTURN = new SafeFlag(false);
    private volatile boolean physicalLinkEnabled = true;
    private volatile boolean physicalLinkCommitted = true;
    private volatile java.util.function.BooleanSupplier physicalAttemptGuard = () -> true;
    private volatile InternalRoutedTransport routedTransport;
    private volatile Instant lastRtcAttemptSince;

    private class NostrRTCListener implements RTCTransportListener {

        private final NostrRTCSocket socket;

        NostrRTCListener(NostrRTCSocket socket) {
            this.socket = socket;
        }

        @Override
        public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
            onLocalRTCIceCandidate(null, candidate);
        }

        private void onLocalRTCIceCandidate(BoundRTCListener source, RTCTransportIceCandidate candidate) {
            synchronized (NostrRTCSocket.this) {
                if (!isCurrentCallback(source)) return;
                localIceCandidates.addIfAbsent(candidate);
                emitCandidates(source);
            }
        }

        @Override
        public void onRTCConnected() {
            onRTCConnected(null);
        }

        private void onRTCConnected(BoundRTCListener source) {
            TransportChange change;
            synchronized (NostrRTCSocket.this) {
                if (!isCurrentCallback(source)) return;
                logger.fine("Link established");
                connected = true;
                turnFallbackAllowed = false;
                pendingConnectionSince = null;
                cancelRtcConnectTimeout();
                change = updateTransportPath(TransportPath.RTC, "rtc-connected");
            }
            emitTransportChange(change);
            if (isCurrentCallback(source)) socket.startChannelResurrection(source);
        }

        @Override
        public void onRTCDisconnected(String reason) {
            onRTCDisconnected(null, reason);
        }

        private void onRTCDisconnected(BoundRTCListener source, String reason) {
            TransportChange change = null;
            synchronized (NostrRTCSocket.this) {
                if (!isCurrentCallback(source)) return;
                connected = false;
                pendingConnectionSince = null;
                logger.fine("RTC disconnected: " + reason);
                cancelRtcConnectTimeout();
                if (activeTransportPath == TransportPath.RTC) {
                    change = updateTransportPath(TransportPath.NONE, "rtc-disconnected");
                }
            }
            emitTransportChange(change);
            for (NostrRTCSocketListener listener : listeners) {
                if (!isCurrentCallback(source)) return;
                try {
                    listener.onRTCSocketTransportDegraded(socket, activeTransportPath, reason);
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Exception in listener", e);
                }
            }
            ensureTurnForDownChannels("rtc-disconnected:" + reason, source, -1L);
        }

        @Override
        public void onRTCBinaryMessage(RTCDataChannel chan, ByteBuffer bbf) {
            onRTCBinaryMessage(null, chan, bbf);
        }

        private void onRTCBinaryMessage(BoundRTCListener source, RTCDataChannel chan, ByteBuffer bbf) {
            // for (NostrRTCSocketListener listener : listeners) {
            //     try {
            //         listener.onRTCSocketMessage(socket, chan,  bbf, false);
            //     } catch (Exception e) {
            //         logger.severe("Error emitting message: " + e.getMessage());
            //     }
            // }
            NostrRTCChannel logicalChannel = channels.get(chan.getName());
            if (logicalChannel == null) {
                logicalChannel = getOrCreateInternalRoutingChannel(source, chan);
            }
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
                if (!deferBinaryUntilChannelRegistered(source, logicalChannel, chan, bbf)) {
                    deliverRegisteredBinary(source, logicalChannel, chan, bbf);
                }
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
            onRTCChannelError(null, chan, e);
        }

        private void onRTCChannelError(BoundRTCListener source, RTCDataChannel chan, Throwable e) {
            logger.severe("RTC Channel Error " + e);
            NostrRTCChannel logicalChannel = channels.get(chan.getName());
            if (logicalChannel != null && isCurrentCallback(source)) {
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
            onRTCChannelReady(null, channel);
        }

        private void onRTCChannelReady(BoundRTCListener source, RTCDataChannel channel) {
            NostrRTCChannel logicalChannel = channels.get(channel.getName());
            if (logicalChannel == null) {
                logicalChannel = getOrCreateInternalRoutingChannel(source, channel);
            }
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
            if (setChannelFromTransport(source, logicalChannel, channel, null)) {
                logger.fine("RTC Channel ready: " + channel.getName());
            }
        }

        @Override
        public void onRTCChannelClosed(RTCDataChannel channel) {
            onRTCChannelClosed(null, channel);
        }

        private void onRTCChannelClosed(BoundRTCListener source, RTCDataChannel channel) {
            NostrRTCChannel logicalChannel = channels.get(channel.getName());
            if (logicalChannel != null) {
                setChannelFromTransport(source, logicalChannel, null, channel);
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
            onRTCBufferedAmountLow(null, channel);
        }

        private void onRTCBufferedAmountLow(BoundRTCListener source, RTCDataChannel channel) {
            NostrRTCChannel logicalChannel = channels.get(channel.getName());
            if (logicalChannel != null && isCurrentCallback(source)) {
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

    /**
     * Native callbacks can already be queued when an obsolete transport is closed.
     */
    private final class BoundRTCListener implements RTCTransportListener {

        private final RTCTransport owner;
        private final boolean offerInitiator;
        private final BooleanSupplier ownerActive;

        private BoundRTCListener(RTCTransport owner, boolean offerInitiator, BooleanSupplier ownerActive) {
            this.owner = owner;
            this.offerInitiator = offerInitiator;
            this.ownerActive = ownerActive;
        }

        private boolean isCurrent() {
            return isCurrentCallback(this);
        }

        @Override
        public void onLocalRTCIceCandidate(RTCTransportIceCandidate candidate) {
            if (isCurrent()) rtcListener.onLocalRTCIceCandidate(this, candidate);
        }

        @Override
        public void onRTCConnected() {
            if (isCurrent()) rtcListener.onRTCConnected(this);
        }

        @Override
        public void onRTCDisconnected(String reason) {
            if (isCurrent()) rtcListener.onRTCDisconnected(this, reason);
        }

        @Override
        public void onRTCBinaryMessage(RTCDataChannel channel, ByteBuffer payload) {
            if (isCurrent()) rtcListener.onRTCBinaryMessage(this, channel, payload);
        }

        @Override
        public void onRTCChannelError(RTCDataChannel channel, Throwable error) {
            if (isCurrent()) rtcListener.onRTCChannelError(this, channel, error);
        }

        @Override
        public void onRTCChannelReady(RTCDataChannel channel) {
            if (isCurrent()) rtcListener.onRTCChannelReady(this, channel);
        }

        @Override
        public void onRTCChannelClosed(RTCDataChannel channel) {
            if (isCurrent()) rtcListener.onRTCChannelClosed(this, channel);
        }

        @Override
        public void onRTCBufferedAmountLow(RTCDataChannel channel) {
            if (isCurrent()) rtcListener.onRTCBufferedAmountLow(this, channel);
        }
    }

    private synchronized boolean isCurrentCallback(BoundRTCListener source) {
        return (
            !stopped &&
            physicalLinkEnabled &&
            physicalAttemptGuard.getAsBoolean() &&
            (source == null || (source == currentRtcListener && transport == source.owner && source.ownerActive.getAsBoolean()))
        );
    }

    private void requireCurrentCallback(BoundRTCListener source) {
        if (!isCurrentCallback(source)) throw new IllegalStateException("RTC transport attempt was superseded");
    }

    private boolean setChannelFromTransport(
        BoundRTCListener source,
        NostrRTCChannel logical,
        RTCDataChannel next,
        RTCDataChannel expectedPrevious
    ) {
        synchronized (this) {
            if (!isCurrentCallback(source) || logical.isClosed()) return false;
            if (expectedPrevious != null && !logical.isUsingNativeChannel(expectedPrevious)) return false;
            logical.updateNativeChannelState(next);
        }
        logical.finishNativeChannelChange(next, () -> isCurrentCallback(source));
        return true;
    }

    private BoundRTCListener installRtcTransport(
        RTCTransport next,
        long expectedGeneration,
        boolean offerInitiator,
        BooleanSupplier ownerActive
    ) {
        BoundRTCListener bound = null;
        synchronized (this) {
            if (
                !stopped &&
                physicalLinkEnabled &&
                physicalAttemptGuard.getAsBoolean() &&
                ownerActive.getAsBoolean() &&
                transport == null &&
                rtcTransportGeneration == expectedGeneration
            ) {
                transport = next;
                bound = new BoundRTCListener(next, offerInitiator, ownerActive);
                currentRtcListener = bound;
            }
        }
        if (bound == null) {
            next.close();
            throw new IllegalStateException("RTC transport attempt was superseded");
        }
        next.addListener(bound);
        List<RTCTransportIceCandidate> pending;
        synchronized (this) {
            requireCurrentCallback(bound);
            pending = new ArrayList<>(pendingRemoteIceCandidates);
            pendingRemoteIceCandidates.clear();
        }
        if (bound.isCurrent() && !pending.isEmpty()) next.addRemoteIceCandidates(pending);
        return bound;
    }

    NostrRTCSocket(
        AsyncExecutor executor,
        NostrRTCPeer remotePeer,
        NostrKeyPair roomKeyPair,
        NostrRTCLocalPeer localPeer,
        RTCSettings settings,
        NostrTURNPool turnPool
    ) {
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local Peer cannot be null");
        this.roomKeyPair = Objects.requireNonNull(roomKeyPair, "Room Key Pair cannot be null");
        this.remotePeer = Objects.requireNonNull(remotePeer, "Remote Peer cannot be null");
        this.turnPool = turnPool;
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

    private static final class TransportChange {

        private final TransportPath previous;
        private final TransportPath next;
        private final String reason;
        private final long generation;

        private TransportChange(TransportPath previous, TransportPath next, String reason, long generation) {
            this.previous = previous;
            this.next = next;
            this.reason = reason;
            this.generation = generation;
        }
    }

    private synchronized TransportChange updateTransportPath(TransportPath next, String reason) {
        TransportPath previous = activeTransportPath;
        if (previous == next) return null;
        activeTransportPath = next;
        return new TransportChange(previous, next, reason, ++transportEventGeneration);
    }

    private void emitTransportChange(TransportChange change) {
        if (change == null) return;
        for (NostrRTCSocketListener listener : listeners) {
            synchronized (this) {
                if (transportEventGeneration != change.generation) return;
            }
            try {
                listener.onRTCSocketTransportSwitch(this, change.previous, change.next, change.reason);
            } catch (Throwable e) {
                logger.severe("Error emitting transport switch: " + e.getMessage());
            }
        }
    }

    private void switchActiveTransport(TransportPath next, String reason) {
        emitTransportChange(updateTransportPath(next, reason));
    }

    private synchronized void cancelRtcConnectTimeout() {
        rtcConnectAttempt++;
        AsyncTask<Void> task = rtcConnectDeadlineTask;
        if (task != null) {
            task.cancel();
            rtcConnectDeadlineTask = null;
        }
    }

    AsyncTask<Void> scheduleChannelMaintenance(Runnable task, long delayMs) {
        return executor.runLater(
            () -> {
                task.run();
                return null;
            },
            delayMs,
            TimeUnit.MILLISECONDS
        );
    }

    private synchronized void scheduleRtcConnectTimeout(String reason, BoundRTCListener source) {
        if (!isCurrentCallback(source)) return;
        cancelRtcConnectTimeout();
        if (connected || transport == null) return;
        long expectedAttempt = rtcConnectAttempt;
        rtcConnectDeadlineTask =
            executor.runLater(
                () -> {
                    synchronized (NostrRTCSocket.this) {
                        if (!isCurrentCallback(source) || connected || rtcConnectAttempt != expectedAttempt) return null;
                        rtcConnectDeadlineTask = null;
                        pendingConnectionSince = null;
                    }
                    // Do not hold the socket monitor while invoking room/application listeners.
                    for (NostrRTCSocketListener listener : listeners) {
                        if (!isCurrentRtcConnectAttempt(source, expectedAttempt)) return null;
                        try {
                            listener.onRTCSocketTransportDegraded(this, activeTransportPath, "rtc-timeout");
                        } catch (Throwable e) {
                            logger.log(Level.SEVERE, "Exception in listener", e);
                        }
                    }
                    ensureTurnForDownChannels("rtc-timeout:" + reason, source, expectedAttempt);
                    return null;
                },
                settings.getP2pGiveupTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );
    }

    private synchronized boolean isCurrentRtcConnectAttempt(BoundRTCListener source, long expectedAttempt) {
        return isCurrentCallback(source) && rtcConnectAttempt == expectedAttempt;
    }

    private void ensureTurnForDownChannels(String reason) {
        ensureTurnForDownChannels(reason, null, -1L);
    }

    private void ensureTurnForDownChannels(String reason, BoundRTCListener source, long expectedAttempt) {
        List<NostrRTCChannel> clearedChannels = new ArrayList<>();
        List<NostrRTCChannel> fallbackChannels = new ArrayList<>();
        synchronized (this) {
            if (!isCurrentCallback(source) || (expectedAttempt >= 0L && rtcConnectAttempt != expectedAttempt)) return;
            if (connected && !forceTURN.get()) {
                logger.fine("Skipping TURN fallback reset because RTC transport is still connected. reason=" + reason);
                return;
            }
            turnFallbackAllowed = true;
            for (NostrRTCChannel channel : channels.values()) {
                if (channel.isClosed()) continue;
                if (channel.isConnected()) {
                    channel.updateNativeChannelState(null);
                    clearedChannels.add(channel);
                } else {
                    fallbackChannels.add(channel);
                }
            }
        }
        for (NostrRTCChannel channel : clearedChannels) {
            channel.finishNativeChannelChange(null, () -> isCurrentCallback(source));
        }
        for (NostrRTCChannel channel : fallbackChannels) {
            if (!isCurrentCallback(source)) return;
            channel.activateFallbackIfNeeded();
        }
        logger.fine(
            "Enabled TURN fallback. reason=" +
            reason +
            ", clearedRtcChannels=" +
            clearedChannels.size() +
            ", bootstrappedTurnChannels=" +
            (clearedChannels.size() + fallbackChannels.size()) +
            ", turnConfigurationComplete=" +
            hasCompleteTurnConfiguration() +
            ", totalChannels=" +
            channels.size()
        );
    }

    @Nullable
    String resolveReceiveTurnUrl() {
        String localTurnServer = localPeer.getTurnServer();
        if (localTurnServer != null && !localTurnServer.isEmpty()) {
            return localTurnServer;
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
        return null;
    }

    boolean hasCompleteTurnConfiguration() {
        String sendTurn = resolveSendTurnUrl();
        if (sendTurn == null || sendTurn.isEmpty()) {
            return false;
        }
        String receiveTurn = resolveReceiveTurnUrl();
        return receiveTurn != null && !receiveTurn.isEmpty();
    }

    void setForceTURN(boolean forceTURN) {
        this.forceTURN.set(forceTURN);
    }

    boolean isForceTURN() {
        return forceTURN.get();
    }

    void setPhysicalLinkEnabled(boolean enabled) {
        RTCTransport previousTransport = null;
        TransportChange change = null;
        List<Runnable> physicalCleanup = new ArrayList<>();
        synchronized (this) {
            if (enabled && (stopped || !physicalAttemptGuard.getAsBoolean())) return;
            if (physicalLinkEnabled == enabled) return;
            physicalLinkEnabled = enabled;
            if (!enabled) {
                connected = false;
                turnFallbackAllowed = false;
                cancelRtcConnectTimeout();
                pendingConnectionSince = null;
                ++rtcTransportGeneration;
                previousTransport = transport;
                transport = null;
                currentRtcListener = null;
                pendingRemoteIceCandidates.clear();
                for (NostrRTCChannel channel : channels.values()) physicalCleanup.add(channel.detachPhysicalTransports());
                change = updateTransportPath(TransportPath.NONE, "physical-link-disabled");
            }
        }
        if (!enabled) {
            if (previousTransport != null) try {
                previousTransport.close();
            } catch (Throwable error) {
                logger.log(Level.FINE, "Failed to close disabled physical RTC transport", error);
            }
            for (Runnable cleanup : physicalCleanup) cleanup.run();
            emitTransportChange(change);
        }
    }

    void activatePhysicalTurnFallback() {
        ensureTurnForDownChannels("physical-turn-fallback");
    }

    boolean canUsePhysicalChannel(String name) {
        return physicalLinkEnabled && (physicalLinkCommitted || !InternalRoutingChannels.isReserved(name));
    }

    void setPhysicalLinkCommitted(boolean committed) {
        if (physicalLinkCommitted == committed) return;
        physicalLinkCommitted = committed;
        if (committed) for (NostrRTCChannel channel : channels.values()) {
            if (channel.isReady()) emitChannelReady(channel);
        }
    }

    boolean isPhysicalLinkEnabled() {
        return physicalLinkEnabled;
    }

    boolean enablePhysicalAttempt(java.util.function.BooleanSupplier guard) {
        synchronized (this) {
            if (stopped || !guard.getAsBoolean()) return false;
            physicalAttemptGuard = guard;
            physicalLinkEnabled = true;
            physicalLinkCommitted = false;
            return true;
        }
    }

    synchronized long getRtcTransportGeneration() {
        return rtcTransportGeneration;
    }

    boolean hasBidirectionalPhysicalTransport() {
        for (NostrRTCChannel channel : channels.values()) {
            if (channel.isPhysicalReady()) return true;
        }
        return false;
    }

    boolean hasProvenPhysicalTransport() {
        for (NostrRTCChannel channel : channels.values()) {
            if (channel.isReplacementReady()) return true;
        }
        return false;
    }

    TransportPath getActiveTransportPath() {
        return activeTransportPath;
    }

    void setRoutedTransport(InternalRoutedTransport routedTransport) {
        this.routedTransport = routedTransport;
    }

    InternalRoutedTransport getRoutedTransport() {
        return routedTransport;
    }

    private void resurrectChannel(NostrRTCChannel channel) {
        final RTCTransport currentTransport;
        final BoundRTCListener source;
        synchronized (this) {
            currentTransport = transport;
            source = currentRtcListener;
        }
        if (currentTransport == null || !currentTransport.isConnected()) return;
        if (!shouldCreateDataChannelLocally()) return;
        synchronized (this) {
            if (
                !isCurrentCallback(source) ||
                transport != currentTransport ||
                channel.isConnected() ||
                channel.isClosed() ||
                channel.isResurrecting()
            ) return;
            channel.setResurrecting(true);
        }
        {
            // logger.fine("Resurrecting channel " + channel.getName());
            currentTransport
                .createDataChannel(
                    channel.getName(),
                    localPeer.getProtocolId(),
                    channel.isOrdered(),
                    channel.isReliable(),
                    channel.getMaxRetransmits(),
                    channel.getMaxPacketLifeTime()
                )
                .then(newChannel -> {
                    if (!setChannelFromTransport(source, channel, newChannel, null)) return null;
                    logger.fine("Channel " + channel.getName() + " resurrected");
                    return null;
                })
                .catchException(e -> {
                    synchronized (NostrRTCSocket.this) {
                        if (!isCurrentCallback(source)) return;
                        channel.setResurrecting(false);
                    }
                    logger.severe("Error resurrecting channel " + channel.getName() + ": " + e.getMessage());
                });
        }
    }

    private void startChannelResurrection(BoundRTCListener source) {
        synchronized (this) {
            if (!isCurrentCallback(source) || channelResurrectionGeneration == rtcTransportGeneration) return;
            channelResurrectionGeneration = rtcTransportGeneration;
        }
        resurrectChannels(source);
    }

    private void resurrectChannels(BoundRTCListener source) {
        executor.runLater(
            () -> {
                if (!isCurrentCallback(source)) return null;
                if (connected) {
                    for (NostrRTCChannel channel : channels.values()) {
                        resurrectChannel(channel);
                    }
                }
                if (isCurrentCallback(source)) this.resurrectChannels(source);
                return null;
            },
            100,
            TimeUnit.MILLISECONDS
        );
    }

    private synchronized boolean shouldCreateDataChannelLocally() {
        return currentRtcListener != null && currentRtcListener.offerInitiator;
    }

    private NostrRTCChannel getOrCreateInternalRoutingChannel(BoundRTCListener source, RTCDataChannel nativeChannel) {
        RouteTransportProfile profile = InternalRoutingChannels.profile(nativeChannel.getName());
        if (profile == null) return null;
        if (nativeChannel.isOrdered() != profile.isOrdered() || nativeChannel.isReliable() != profile.isReliable()) {
            logger.warning("Rejected internal routing channel with mismatched transport profile");
            return null;
        }
        return createChannel(
            source,
            nativeChannel.getName(),
            profile.isOrdered(),
            profile.isReliable(),
            profile.getMaxRetransmits(),
            profile.getMaxPacketLifeTime()
        );
    }

    private static String normalizeChannelName(String name) {
        String nativeName = DEFAULT_CHANNEL_NAME.equals(name) ? RTCTransport.DEFAULT_CHANNEL : name;
        if (nativeName == null || nativeName.isEmpty()) {
            nativeName = DEFAULT_CHANNEL_NAME;
        }
        return nativeName;
    }

    @SuppressWarnings("unused")
    private static boolean isDefaultChannelName(String channelName) {
        if (channelName == null) {
            return false;
        }
        return DEFAULT_CHANNEL_NAME.equals(channelName) || RTCTransport.DEFAULT_CHANNEL.equals(channelName);
    }

    void emitChannelReady(NostrRTCChannel channel) {
        if (channel == null || channel.isClosed()) return;
        boolean turnReady = channel.isTurnReady();
        prepareChannelReadyNotification(channel, turnReady, () -> true).run();
    }

    synchronized Runnable prepareChannelReadyNotification(
        NostrRTCChannel channel,
        boolean turnReady,
        BooleanSupplier ownerActive
    ) {
        if (channel == null || channel.isClosed() || !ownerActive.getAsBoolean()) return () -> {};
        TransportChange change = null;
        if (connected) {
            change = updateTransportPath(TransportPath.RTC, "rtc-channel-ready");
        } else if (turnReady) {
            pendingConnectionSince = null;
            change = updateTransportPath(TransportPath.TURN, "turn-channel-ready");
        }
        TransportChange capturedChange = change;
        return () -> {
            if (!ownerActive.getAsBoolean() || channel.isClosed()) return;
            emitTransportChange(capturedChange);
            for (NostrRTCSocketListener listener : listeners) {
                if (!ownerActive.getAsBoolean() || channel.isClosed()) return;
                if (InternalRoutingChannels.isReserved(channel.getName()) && !internalListeners.contains(listener)) continue;
                try {
                    listener.onRTCChannelReady(channel);
                } catch (Throwable error) {
                    logger.log(Level.SEVERE, "Exception in listener", error);
                }
            }
        };
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
        return turnFallbackAllowed && hasCompleteTurnConfiguration();
    }

    /**
     * Return true if the connection is established.
     * @return True if the connection is established.
     */
    boolean isRTCConnected() {
        return connected;
    }

    boolean hasUsableTransport() {
        if (!physicalLinkEnabled) {
            return false;
        }
        if (connected) {
            return true;
        }
        if (activeTransportPath == TransportPath.TURN) {
            return true;
        }
        for (NostrRTCChannel channel : channels.values()) {
            if (!channel.isClosed() && channel.hasUsableTransport()) {
                return true;
            }
        }
        return false;
    }

    boolean shouldAttemptRtcUpgrade() {
        if (stopped || !physicalLinkEnabled || forceTURN.get() || activeTransportPath != TransportPath.TURN) {
            return false;
        }
        if (transport != null || isPendingConnection()) {
            return false;
        }
        Instant lastAttempt = lastRtcAttemptSince;
        if (lastAttempt == null) {
            return true;
        }
        return lastAttempt.plusMillis(RTC_UPGRADE_INTERVAL_MS).isBefore(Instant.now());
    }

    public boolean isClosed() {
        return stopped;
    }

    /**
     * Close the socket.
     */
    void close() {
        synchronized (this) {
            stopped = true;
            channelRegistrations.clear();
        }
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
        internalListeners.clear();
        connected = false;
        switchActiveTransport(TransportPath.NONE, "socket-closed");
    }

    void reset() {
        detachRtcTransport(true, "rtc-reset");
    }

    void prepareRtcTransportAttempt() {
        prepareRtcTransportAttempt(() -> true);
    }

    void prepareRtcTransportAttempt(java.util.function.BooleanSupplier attemptActive) {
        if (!physicalLinkEnabled) return;
        detachRtcTransport(false, "rtc-prepare-attempt", attemptActive);
    }

    private void detachRtcTransport(boolean resetChannels, String reason) {
        detachRtcTransport(resetChannels, reason, () -> true);
    }

    /**
     * A prepared native attempt owns captured cleanup and one exact transport generation.
     */
    final class PreparedRtcAttempt {

        private final NostrRTCSocket owner = NostrRTCSocket.this;
        private final long generation;
        private final BooleanSupplier ownerActive;
        private final Runnable cleanup;
        private boolean cleanupStarted;
        private boolean cleanupComplete;
        private boolean claimed;

        private PreparedRtcAttempt(long generation, BooleanSupplier ownerActive, Runnable cleanup) {
            this.generation = generation;
            this.ownerActive = ownerActive;
            this.cleanup = cleanup;
        }

        void cleanup() {
            synchronized (NostrRTCSocket.this) {
                if (cleanupStarted) return;
                cleanupStarted = true;
            }
            try {
                cleanup.run();
                synchronized (NostrRTCSocket.this) {
                    cleanupComplete = true;
                }
            } catch (RuntimeException | Error failure) {
                synchronized (NostrRTCSocket.this) {
                    if (!claimed && rtcTransportGeneration == generation) {
                        ++rtcTransportGeneration;
                        pendingConnectionSince = null;
                    }
                }
                throw failure;
            }
        }

        boolean isCurrent() {
            synchronized (NostrRTCSocket.this) {
                return (
                    cleanupComplete &&
                    !stopped &&
                    physicalLinkEnabled &&
                    rtcTransportGeneration == generation &&
                    ownerActive.getAsBoolean()
                );
            }
        }
    }

    synchronized PreparedRtcAttempt reserveRtcTransportAttempt(BooleanSupplier ownerActive) {
        return reserveRtcTransportAttempt(ownerActive, true);
    }

    synchronized PreparedRtcAttempt reserveRtcTransportAttempt(BooleanSupplier ownerActive, boolean replaceTransport) {
        if (stopped || !physicalLinkEnabled || !ownerActive.getAsBoolean()) return null;
        if (!replaceTransport && transport != null) return null;
        // A fresh incoming description retains ICE received before its offer.
        Runnable cleanup = replaceTransport ? detachRtcTransportState(false, "rtc-prepare-attempt") : () -> {};
        lastRtcAttemptSince = Instant.now();
        pendingConnectionSince = lastRtcAttemptSince;
        return new PreparedRtcAttempt(rtcTransportGeneration, ownerActive, cleanup);
    }

    private synchronized boolean claimPreparedAttempt(PreparedRtcAttempt attempt) {
        if (attempt == null) return true;
        if (attempt.owner != this || attempt.claimed || !attempt.isCurrent()) return false;
        attempt.claimed = true;
        return true;
    }

    private void detachRtcTransport(boolean resetChannels, String reason, java.util.function.BooleanSupplier attemptActive) {
        Runnable cleanup;
        synchronized (this) {
            if (!attemptActive.getAsBoolean()) return;
            cleanup = detachRtcTransportState(resetChannels, reason);
        }
        cleanup.run();
    }

    private synchronized Runnable detachRtcTransportState(boolean resetChannels, String reason) {
        RTCTransport previousTransport;
        AsyncTask<Void> previousCandidateEmission;
        TransportChange change = null;
        connected = false;
        if (resetChannels) turnFallbackAllowed = false;
        cancelRtcConnectTimeout();
        ++rtcTransportGeneration;
        previousTransport = transport;
        transport = null;
        currentRtcListener = null;
        previousCandidateEmission = delayedCandidateEmission;
        delayedCandidateEmission = null;
        ++candidateEmissionGeneration;
        pendingConnectionSince = null;
        localIceCandidates.clear();
        pendingRemoteIceCandidates.clear();
        for (NostrRTCChannel channel : channels.values()) {
            channel.setResurrecting(false);
            // Closing callbacks are deliberately invalidated, so detach their native handles here.
            channel.updateNativeChannelState(null);
        }
        if (activeTransportPath == TransportPath.RTC) {
            change = updateTransportPath(TransportPath.NONE, reason);
        }
        TransportChange detachedChange = change;
        return () -> {
            // Native close and application notifications run after releasing lifecycle monitors.
            if (previousTransport != null) try {
                previousTransport.close();
            } catch (Exception error) {
                logger.log(Level.FINE, "Error closing obsolete RTC transport", error);
            }
            if (previousCandidateEmission != null) previousCandidateEmission.cancel();
            emitTransportChange(detachedChange);
        };
    }

    /**
     * Add a lifecycle listener to this socket.
     *
     * @param listener listener to add
     */
    public void addListener(NostrRTCSocketListener listener) {
        listeners.add(listener);
    }

    void addInternalListener(NostrRTCSocketListener listener) {
        internalListeners.add(listener);
        listeners.add(listener);
    }

    /**
     * Remove a lifecycle listener from this socket.
     *
     * @param listener listener to remove
     */
    public void removeListener(NostrRTCSocketListener listener) {
        listeners.remove(listener);
        internalListeners.remove(listener);
    }

    // internal, emit all candidates after a delay
    private synchronized void emitCandidates() {
        emitCandidates(currentRtcListener);
    }

    private synchronized void emitCandidates(BoundRTCListener source) {
        if (!isCurrentCallback(source)) return;
        if (delayedCandidateEmission != null) delayedCandidateEmission.cancel();
        long generation = ++candidateEmissionGeneration;
        long transportGeneration = rtcTransportGeneration;
        delayedCandidateEmission =
            executor.runLater(
                () -> {
                    List<RTCTransportIceCandidate> candidates;
                    synchronized (NostrRTCSocket.this) {
                        if (!isCurrentCallback(source) || generation != candidateEmissionGeneration) return null;
                        delayedCandidateEmission = null;
                        candidates = new ArrayList<RTCTransportIceCandidate>(localIceCandidates);
                    }
                    for (NostrRTCSocketListener listener : listeners) {
                        synchronized (NostrRTCSocket.this) {
                            if (!isCurrentCallback(source) || generation != candidateEmissionGeneration) return null;
                        }
                        try {
                            listener.onRTCSocketRouteUpdate(this, candidates, resolveReceiveTurnUrl(), transportGeneration);
                        } catch (Throwable error) {
                            logger.log(Level.SEVERE, "Exception in listener", error);
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
        return listen(() -> true);
    }

    AsyncTask<NostrRTCOfferSignal> listen(java.util.function.BooleanSupplier attemptActive) {
        return listen(attemptActive, null);
    }

    AsyncTask<NostrRTCOfferSignal> listen(PreparedRtcAttempt attempt) {
        return listen(attempt.ownerActive, attempt);
    }

    private AsyncTask<NostrRTCOfferSignal> listen(BooleanSupplier attemptActive, PreparedRtcAttempt attempt) {
        try {
            final long generation;
            synchronized (this) {
                if (stopped || !physicalLinkEnabled || !physicalAttemptGuard.getAsBoolean() || !attemptActive.getAsBoolean()) {
                    if (attempt != null) return AsyncTask.failed(
                        new IllegalStateException("RTC transport attempt was superseded")
                    );
                    throw new IllegalStateException("Physical peer link is disabled");
                }
                if (!claimPreparedAttempt(attempt)) return AsyncTask.failed(
                    new IllegalStateException("RTC transport attempt was superseded")
                );
                if (this.transport != null) throw new IllegalStateException("Already connected");
                generation = rtcTransportGeneration;
                this.lastRtcAttemptSince = Instant.now();
                this.pendingConnectionSince = Instant.now();
            }

            logger.fine("Listening for RTC connections on connection ID: " + localPeer.getSessionId());
            // useTURN(false);

            NGEPlatform platform = NGEUtils.getPlatform();
            logger.fine("Creating RTC transport for connection ID: " + localPeer.getSessionId());

            RTCTransport currentTransport = platform.newRTCTransport(
                settings.getP2pAttemptTimeout(),
                localPeer.getSessionId(),
                localPeer.getStunServers()
            );
            BoundRTCListener source = installRtcTransport(currentTransport, generation, true, attemptActive);

            logger.fine("Initiating RTC channel for connection ID: " + localPeer.getSessionId());
            scheduleRtcConnectTimeout("listen", source);

            return currentTransport
                .listen()
                .then(offerString -> {
                    requireCurrentCallback(source);
                    logger.fine("Created RTC offer");
                    NostrRTCOfferSignal offer = new NostrRTCOfferSignal(
                        localPeer.getSigner(),
                        roomKeyPair,
                        localPeer,
                        offerString
                    );
                    logger.fine("Ready to send RTC offer");
                    requireCurrentCallback(source);
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
        return connect(offerOrAnswer, () -> true);
    }

    AsyncTask<NostrRTCAnswerSignal> connect(NostrRTCSignal offerOrAnswer, java.util.function.BooleanSupplier attemptActive) {
        return connect(offerOrAnswer, attemptActive, null);
    }

    AsyncTask<NostrRTCAnswerSignal> connect(NostrRTCSignal offerOrAnswer, PreparedRtcAttempt attempt) {
        return connect(offerOrAnswer, attempt.ownerActive, attempt);
    }

    private AsyncTask<NostrRTCAnswerSignal> connect(
        NostrRTCSignal offerOrAnswer,
        BooleanSupplier attemptActive,
        PreparedRtcAttempt attempt
    ) {
        Objects.requireNonNull(offerOrAnswer);
        final long generation;
        synchronized (this) {
            if (stopped || !physicalLinkEnabled || !physicalAttemptGuard.getAsBoolean() || !attemptActive.getAsBoolean()) {
                return AsyncTask.failed(new IllegalStateException("Physical peer link is disabled"));
            }
            if (!claimPreparedAttempt(attempt)) return AsyncTask.failed(
                new IllegalStateException("RTC transport attempt was superseded")
            );
            generation = rtcTransportGeneration;
            this.lastRtcAttemptSince = Instant.now();
            this.pendingConnectionSince = Instant.now();
        }
        logger.fine("Connecting to RTC socket");
        // useTURN(false);

        NGEPlatform platform = NGEUtils.getPlatform();

        String connectString;
        RTCTransport currentTransport;
        BoundRTCListener source;
        if (offerOrAnswer instanceof NostrRTCOfferSignal) {
            if (this.transport != null) throw new IllegalStateException("Already connected");
            currentTransport =
                platform.newRTCTransport(settings.getP2pAttemptTimeout(), localPeer.getSessionId(), localPeer.getStunServers());
            source = installRtcTransport(currentTransport, generation, false, attemptActive);
            logger.fine("Use offer to connect");
            this.remotePeer.merge(((NostrRTCOfferSignal) offerOrAnswer).getPeer());
            // this.remotePeer =
            //     Objects.requireNonNull(((NostrRTCOfferSignal) offerOrAnswer).getPeer(), "Remote Peer cannot be null");
            emitCandidates();
            connectString = ((NostrRTCOfferSignal) offerOrAnswer).getOfferString();
        } else if (offerOrAnswer instanceof NostrRTCAnswerSignal) {
            // logger.fine("Use answer to connect");
            synchronized (this) {
                if (generation != rtcTransportGeneration || !attemptActive.getAsBoolean()) {
                    return AsyncTask.failed(new IllegalStateException("Obsolete RTC description"));
                }
                if (this.transport == null) throw new IllegalStateException("Not connected");
                currentTransport = this.transport;
                source = currentRtcListener;
            }
            this.remotePeer.merge(((NostrRTCAnswerSignal) offerOrAnswer).getPeer());
            emitCandidates();
            connectString = ((NostrRTCAnswerSignal) offerOrAnswer).getSdp();
        } else {
            throw new IllegalArgumentException("Invalid RTC signal type");
        }

        scheduleRtcConnectTimeout("connect", source);
        return currentTransport
            .connect(connectString)
            .then(answerString -> {
                requireCurrentCallback(source);
                if (answerString == null) {
                    logger.fine("Connected to RTC socket");
                    return null;
                }
                logger.fine("Received RTC answer");
                NostrRTCAnswerSignal answer = new NostrRTCAnswerSignal(
                    localPeer.getSigner(),
                    roomKeyPair,
                    localPeer,
                    answerString
                );
                requireCurrentCallback(source);
                return answer;
            });
    }

    /**
     * Merge remote ICE candidates with the already
     * tracked candidates.
     * @param candidate The remote ICE candidates.
     */
    void mergeRemoteRTCIceCandidate(NostrRTCRouteSignal candidate) {
        mergeRemoteRTCIceCandidate(candidate, () -> true);
    }

    void mergeRemoteRTCIceCandidate(NostrRTCRouteSignal candidate, java.util.function.BooleanSupplier attemptActive) {
        Objects.requireNonNull(candidate);
        Collection<RTCTransportIceCandidate> candidates = candidate.getCandidates();
        RTCTransport currentTransport;
        synchronized (this) {
            if (!physicalLinkEnabled || stopped || !attemptActive.getAsBoolean()) return;
            candidate.updatePeer(remotePeer);
            currentTransport = transport;
            if (currentTransport == null) {
                for (RTCTransportIceCandidate ice : candidates) {
                    if (pendingRemoteIceCandidates.size() >= MAX_PENDING_REMOTE_ICE_CANDIDATES) break;
                    if (!pendingRemoteIceCandidates.contains(ice)) pendingRemoteIceCandidates.add(ice);
                }
            }
        }
        if (currentTransport == null) {
            if (attemptActive.getAsBoolean() && (turnFallbackAllowed || forceTURN.get())) ensureTurnForDownChannels(
                "remote-turn-route"
            );
            return;
        }
        if (attemptActive.getAsBoolean()) currentTransport.addRemoteIceCandidates(candidates);
    }

    private boolean deferBinaryUntilChannelRegistered(
        BoundRTCListener source,
        NostrRTCChannel logicalChannel,
        RTCDataChannel nativeChannel,
        ByteBuffer data
    ) {
        synchronized (this) {
            if (!isCurrentCallback(source) || logicalChannel.isClosed()) return true;
            PendingChannelRegistration registration = channelRegistrations.get(logicalChannel);
            if (registration == null) return false;
            int bytes = data.remaining();
            if (
                registration.messages.size() >= MAX_PENDING_CHANNEL_MESSAGES ||
                bytes > MAX_PENDING_CHANNEL_BYTES - registration.pendingBytes
            ) {
                // Do not deduplicate dropped frames; a later transport retry can still deliver them.
                logger.fine("Dropping RTC frame while channel registration queue is full");
                return true;
            }
            ByteBuffer copy = ByteBuffer.allocate(bytes);
            copy.put(data.asReadOnlyBuffer()).flip();
            registration.messages.addLast(new PendingChannelMessage(source, nativeChannel, copy));
            registration.pendingBytes += bytes;
            return true;
        }
    }

    private void deliverRegisteredBinary(
        BoundRTCListener source,
        NostrRTCChannel logicalChannel,
        RTCDataChannel nativeChannel,
        ByteBuffer data
    ) {
        if (
            !logicalChannel.isClosed() &&
            setChannelFromTransport(source, logicalChannel, nativeChannel, null) &&
            isCurrentCallback(source)
        ) {
            logicalChannel.onRTCSocketMessage(data);
        }
    }

    private void finishChannelRegistration(NostrRTCChannel logicalChannel) {
        while (true) {
            PendingChannelMessage message;
            synchronized (this) {
                PendingChannelRegistration registration = channelRegistrations.get(logicalChannel);
                if (registration == null) return;
                if (stopped || logicalChannel.isClosed() || channels.get(logicalChannel.getName()) != logicalChannel) {
                    channelRegistrations.remove(logicalChannel);
                    return;
                }
                message = registration.messages.pollFirst();
                if (message == null) {
                    channelRegistrations.remove(logicalChannel);
                    return;
                }
                registration.pendingBytes -= message.data.remaining();
            }
            // Keep the gate installed until draining finishes, so a newer callback cannot overtake queued frames.
            try {
                deliverRegisteredBinary(message.source, logicalChannel, message.nativeChannel, message.data);
            } catch (Throwable error) {
                logger.log(Level.WARNING, "Failed to deliver RTC frame after channel registration", error);
            }
        }
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
        return createChannel(null, name, ordered, reliable, maxRetransmits, maxPacketLifeTime);
    }

    private NostrRTCChannel createChannel(
        BoundRTCListener callbackSource,
        String name,
        boolean ordered,
        boolean reliable,
        @Nullable Integer maxRetransmits,
        @Nullable Duration maxPacketLifeTime
    ) {
        return createChannel(callbackSource, name, ordered, reliable, maxRetransmits, maxPacketLifeTime, false, false);
    }

    /**
     * Room publication can race retirement after releasing the room monitor.
     */
    NostrRTCChannel createInitialDefaultChannel() {
        return createLogicalChannelIfOpen(DEFAULT_CHANNEL_NAME, true, true, null, null);
    }

    NostrRTCChannel createLogicalChannelIfOpen(
        String name,
        boolean ordered,
        boolean reliable,
        @Nullable Integer maxRetransmits,
        @Nullable Duration maxPacketLifeTime
    ) {
        return createChannel(null, name, ordered, reliable, maxRetransmits, maxPacketLifeTime, true, false);
    }

    /**
     * Deferred physical work must not publish channels on a retired or disabled socket.
     */
    NostrRTCChannel createPhysicalChannel(
        String name,
        boolean ordered,
        boolean reliable,
        @Nullable Integer maxRetransmits,
        @Nullable Duration maxPacketLifeTime
    ) {
        return createChannel(null, name, ordered, reliable, maxRetransmits, maxPacketLifeTime, true, true);
    }

    private NostrRTCChannel createChannel(
        BoundRTCListener callbackSource,
        String name,
        boolean ordered,
        boolean reliable,
        @Nullable Integer maxRetransmits,
        @Nullable Duration maxPacketLifeTime,
        boolean requireOpen,
        boolean requirePhysical
    ) {
        String nativeName = normalizeChannelName(name);
        final String channelName = nativeName;
        Integer normalizedMaxRetransmits = maxRetransmits != null ? maxRetransmits : Integer.valueOf(0);
        NostrRTCChannel chan;
        boolean created;
        synchronized (this) {
            if (
                (requireOpen && stopped) || (requirePhysical && (!physicalLinkEnabled || !physicalAttemptGuard.getAsBoolean()))
            ) return null;
            if (callbackSource != null && !isCurrentCallback(callbackSource)) return null;
            chan = channels.get(channelName);
            created = chan == null;
            if (created) {
                chan = new NostrRTCChannel(channelName, this, ordered, reliable, normalizedMaxRetransmits, maxPacketLifeTime);
                channelRegistrations.put(chan, new PendingChannelRegistration());
                channels.put(channelName, chan);
            }
        }
        if (created) {
            try {
                for (NostrRTCSocketListener listener : listeners) {
                    synchronized (this) {
                        // Publication was owner-guarded, but registration belongs to the persistent logical channel.
                        if (stopped || chan.isClosed() || channels.get(channelName) != chan) break;
                    }
                    if (InternalRoutingChannels.isReserved(channelName) && !internalListeners.contains(listener)) continue;
                    try {
                        listener.onRTCChannel(chan);
                    } catch (Throwable error) {
                        logger.log(Level.SEVERE, "Exception in listener", error);
                    }
                }
            } finally {
                finishChannelRegistration(chan);
            }
        }
        RTCTransport currentTransport;
        BoundRTCListener source;
        synchronized (this) {
            currentTransport = this.transport;
            source = currentRtcListener;
        }
        if (currentTransport != null) {
            RTCDataChannel existingChannel = currentTransport.getDataChannel(channelName);
            if (existingChannel != null) setChannelFromTransport(source, chan, existingChannel, null);
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
        if (!physicalLinkEnabled) return false;
        if (pendingConnectionSince == null) return false;
        if (connected || stopped) return false;
        return pendingConnectionSince.plus(settings.getPeerExpiration()).isAfter(Instant.now());
    }
}
