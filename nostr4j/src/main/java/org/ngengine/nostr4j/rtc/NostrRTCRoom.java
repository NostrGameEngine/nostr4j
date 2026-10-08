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
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.PhysicalConnectionManager.Attempt;
import org.ngengine.nostr4j.rtc.PhysicalConnectionManager.Phase;
import org.ngengine.nostr4j.rtc.delivery.DeliveryFailures;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCChannelListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCPeerSocketAvailableListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerDisconnectListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerDiscoveredListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCRoomPeerMessageListener;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.nostr4j.rtc.routing.NeighborTrafficLimiter;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutedTransportContext;
import org.ngengine.nostr4j.rtc.routing.RoutedTransportEngine;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastAck;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastContext;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastEngine;
import org.ngengine.nostr4j.rtc.routing.topology.BoundedOverlaySelector;
import org.ngengine.nostr4j.rtc.routing.topology.DesiredDirectEdge;
import org.ngengine.nostr4j.rtc.routing.topology.DirectNeighborManager;
import org.ngengine.nostr4j.rtc.routing.topology.MutualTopologyGraphBuilder;
import org.ngengine.nostr4j.rtc.routing.topology.OverlayEdgePriority;
import org.ngengine.nostr4j.rtc.routing.topology.OverlayPlan;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyControlPlane;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyNeighbor;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswerSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLinkSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.SafeFlag;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

public final class NostrRTCRoom implements Closeable {

    private static final Logger logger = Logger.getLogger(NostrRTCRoom.class.getName());
    private static final long DEFAULT_QUEUED_SEND_TIMEOUT_MS = 30_000L;

    private final Map<NostrRTCPeer, NostrRTCSocket> connections = new ConcurrentHashMap<>();
    private final Map<NostrRTCChannel, BlockingPacketQueue<NostrRTCChannel.PreparedPacket>> pendingSends =
        new ConcurrentHashMap<>();
    private final Collection<NostrPublicKey> bannedPeers = new CopyOnWriteArrayList<>();

    private final List<NostrRTCPeerSocketAvailableListener> onSocketAvailable = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerDisconnectListener> onDisconnectionListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerMessageListener> onMessageListeners = new CopyOnWriteArrayList<>();
    private final List<NostrRTCRoomPeerDiscoveredListener> onPeerDiscoveredListeners = new CopyOnWriteArrayList<>();

    private final NostrRTCLocalPeer localPeer;
    private final NostrRTCSignaling signaling;
    private final RTCSettings settings;
    private final LongSupplier queuedSendClock;
    private final AsyncExecutor executor;
    private final NostrKeyPair roomKeyPair;
    private final NostrTURNPool turnPool;
    private final SafeFlag forceTURN = new SafeFlag(false);
    private final RoutingScope routingScope;
    private final NodeId localNodeId;
    private final NostrKeyPair routingKeyPair;
    private final NeighborTrafficLimiter routingTrafficLimiter = new NeighborTrafficLimiter();
    private final TopologyControlPlane topologyControl;
    private final RoutedTransportEngine routingEngine;
    private final BroadcastEngine broadcastEngine;
    private final DirectNeighborManager neighborManager = new DirectNeighborManager();
    private final MutualTopologyGraphBuilder graphBuilder = new MutualTopologyGraphBuilder();
    private volatile TopologyGraph routingGraph = new TopologyGraph(Collections.emptySet(), Collections.emptySet());
    private final Map<String, TopologyGraph> recentRoutingGraphs = new LinkedHashMap<String, TopologyGraph>();
    private volatile boolean topologyRefreshScheduled;
    private volatile boolean closed;
    private volatile boolean started;
    private final PhysicalConnectionManager physicalConnections;
    private final AtomicBoolean directRefreshRunning = new AtomicBoolean();
    private volatile Instant topologyEvaluatedAt = Instant.EPOCH;

    private void drainQueue(NostrRTCChannel channel) {
        BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue = pendingSends.get(channel);
        if (queue != null) {
            queue.restart();
        }
    }

    private long getQueuedSendTimeoutMs() {
        try {
            Object value = settings.getClass().getMethod("getQueuedSendTimeout").invoke(settings);
            if (value instanceof Duration) {
                return Math.max(0L, ((Duration) value).toMillis());
            }
        } catch (ReflectiveOperationException ignored) {}
        return DEFAULT_QUEUED_SEND_TIMEOUT_MS;
    }

    private BlockingPacketQueue<NostrRTCChannel.PreparedPacket> newPendingSendQueue(NostrRTCChannel chan) {
        return new BlockingPacketQueue<NostrRTCChannel.PreparedPacket>(
            new BlockingPacketQueue.PacketHandler<NostrRTCChannel.PreparedPacket>() {
                private volatile long attemptGeneration;

                @Override
                public AsyncTask<Boolean> handle(NostrRTCChannel.PreparedPacket packet) {
                    return chan.write(packet);
                }

                @Override
                public AsyncTask<Boolean> handle(NostrRTCChannel.PreparedPacket packet, BooleanSupplier attemptActive) {
                    attemptGeneration = chan.getWriteGeneration();
                    return chan.write(packet, () -> !closed && !chan.getSocket().isClosed() && attemptActive.getAsBoolean());
                }

                @Override
                public boolean isInFlightValid() {
                    return !closed && !chan.getSocket().isClosed() && attemptGeneration == chan.getWriteGeneration();
                }

                @Override
                public boolean isReady() {
                    return chan.isReady();
                }

                @Override
                public boolean shouldPauseOnError(Throwable error) {
                    return DeliveryFailures.isRetryable(error);
                }
            },
            logger,
            "Failed to send data to peer",
            1000L,
            6000L,
            getQueuedSendTimeoutMs(),
            queuedSendClock
        );
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
        public void onReceiveLinkSignal(NostrRTCLinkSignal signal) {
            NostrRTCRoom.this.onReceiveLinkSignal(signal);
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
            NostrRTCRoom.this.onRTCSocketLocalIceCandidate(socket, candidates, turnServer, socket.getRtcTransportGeneration());
        }

        @Override
        public void onRTCSocketRouteUpdate(
            NostrRTCSocket socket,
            Collection<RTCTransportIceCandidate> candidates,
            String turnServer,
            long generation
        ) {
            NostrRTCRoom.this.onRTCSocketLocalIceCandidate(socket, candidates, turnServer, generation);
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
            scheduleTopologyRefresh();
        }

        @Override
        public void onRTCSocketTransportSwitch(
            NostrRTCSocket socket,
            NostrRTCSocket.TransportPath from,
            NostrRTCSocket.TransportPath to,
            String reason
        ) {
            scheduleTopologyRefresh();
        }

        @Override
        public void onRTCSocketTransportDegraded(NostrRTCSocket socket, NostrRTCSocket.TransportPath active, String reason) {
            scheduleTopologyRefresh();
        }

        @Override
        public void onRTCSocketMessage(NostrRTCChannel channel, ByteBuffer bbf, boolean turn) {
            NostrRTCSocket socket = channel.getSocket();
            NostrRTCPeer remotePeer = socket.getRemotePeer();
            if (remotePeer == null || remotePeer.getPubkey() == null) return;
            if (InternalRoutingChannels.isReserved(channel.getName())) {
                NodeId previous = NodeId.derive(routingScope, remotePeer.getPubkey(), remotePeer.getSessionId());
                if (InternalRoutingChannels.LINK_ADMISSION.equals(channel.getName())) {
                    onPhysicalAdmissionFrame(socket, bbf);
                    return;
                }
                if (!physicalConnections.committed(remotePeer)) return;
                if (InternalRoutingChannels.CONTROL.equals(channel.getName())) {
                    routingEngine.onDirectControl(previous, bbf);
                } else if (channel.getName().startsWith(InternalRoutingChannels.BROADCAST_PREFIX)) {
                    broadcastEngine.onTreeFrame(previous, bbf, Instant.now());
                } else {
                    routingEngine.onDirectData(previous, bbf);
                }
                return;
            }
            for (NostrRTCRoomPeerMessageListener listener : onMessageListeners) {
                try {
                    listener.onRoomPeerMessage(remotePeer, socket, channel, bbf, turn);
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Exception in listener", e);
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
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Exception in listener", e);
                }
            }
            drainQueue(channel);
        }
    };

    /**
     * Creates a room using the supplied signaling pool and RTC configuration.
     *
     * <p>The constructor calls {@link NostrPool#ensureRelay(String)} for every
     * URL in {@link RTCSettings#getSignalingRelays()}. </p>
     *
     * <p>The caller owns {@code signalingPool}. To reuse its relay connections,
     * pass the same pool to rooms with the same signaling relay list, and close
     * it after those rooms have closed.</p>
     *
     * @param settings RTC and signaling configuration for this room
     * @param localPeer local identity and session for this room
     * @param roomKeyPair key pair identifying the room
     * @param signalingPool caller-owned pool used for signaling
     * @param turnPool optional TURN pool, or {@code null} if TURN is unavailable
     * @throws NullPointerException if {@code settings}, {@code localPeer},
     *         {@code roomKeyPair}, or {@code signalingPool} is null
     */
    public NostrRTCRoom(
        RTCSettings settings,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrPool signalingPool,
        NostrTURNPool turnPool
    ) {
        this(settings, localPeer, roomKeyPair, signalingPool, turnPool, System::currentTimeMillis);
    }

    NostrRTCRoom(
        RTCSettings settings,
        NostrRTCLocalPeer localPeer,
        NostrKeyPair roomKeyPair,
        NostrPool signalingPool,
        NostrTURNPool turnPool,
        LongSupplier queuedSendClock
    ) {
        this.roomKeyPair = Objects.requireNonNull(roomKeyPair, "Room key pair cannot be null");
        this.settings = Objects.requireNonNull(settings, "Settings cannot be null");
        this.queuedSendClock = Objects.requireNonNull(queuedSendClock, "Queued send clock cannot be null");
        this.localPeer = Objects.requireNonNull(localPeer, "Local peer cannot be null");
        this.physicalConnections =
            new PhysicalConnectionManager(
                settings,
                localPeer.getPubkey(),
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
                () -> {
                    byte[] bytes = NGEPlatform.get().randomBytes(2);
                    return ((bytes[0] & 255) * 256 + (bytes[1] & 255)) / 65535d;
                }
            );
        this.turnPool = turnPool;
        NostrPool checkedPool = Objects.requireNonNull(signalingPool, "Signaling pool cannot be null");
        for (String relay : settings.getSignalingRelays()) checkedPool.ensureRelay(relay);
        this.routingScope =
            new RoutingScope(roomKeyPair.getPublicKey(), localPeer.getProtocolId(), localPeer.getApplicationId());
        this.localNodeId = NodeId.derive(routingScope, localPeer.getPubkey(), localPeer.getSessionId());
        this.routingKeyPair = new NostrKeyPair();
        this.signaling =
            new NostrRTCSignaling(
                settings,
                localPeer.getApplicationId(),
                localPeer.getProtocolId(),
                localPeer,
                roomKeyPair,
                checkedPool
            );
        this.signaling.addListener(listener);
        this.executor = NGEUtils.getPlatform().newAsyncExecutor(NostrRTCRoom.class);
        this.topologyControl =
            new TopologyControlPlane(
                routingScope,
                localPeer,
                roomKeyPair,
                routingKeyPair,
                checkedPool,
                settings.getSignalingAnnounceExpiration(),
                settings.getSignalingLoopInterval()
            );
        this.topologyControl.setListener(this::scheduleTopologyRefresh);
        this.routingEngine =
            new RoutedTransportEngine(
                localNodeId,
                routingKeyPair,
                new RoutedTransportContext() {
                    @Override
                    public TopologyGraph currentGraph() {
                        return routingGraph;
                    }

                    @Override
                    public Collection<org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot> topologySnapshots(
                        Instant now
                    ) {
                        return topologyControl.getSnapshots(now);
                    }

                    @Override
                    public NodeId destinationFor(NostrRTCChannel channel) {
                        NostrRTCPeer peer = channel.getSocket().getRemotePeer();
                        return NodeId.derive(routingScope, peer.getPubkey(), peer.getSessionId());
                    }

                    @Override
                    public boolean hasUsableDirectTurn(NostrRTCChannel channel) {
                        NostrRTCSocket socket = channel.getSocket();
                        return (
                            physicalConnections.committed(socket.getRemotePeer()) &&
                            socket.getActiveTransportPath() == NostrRTCSocket.TransportPath.TURN &&
                            channel.isTurnReady()
                        );
                    }

                    @Override
                    public AsyncTask<Boolean> sendToDirectNeighbor(
                        NodeId neighbor,
                        String internalChannel,
                        RouteTransportProfile profile,
                        ByteBuffer payload
                    ) {
                        return sendInternalToNeighbor(neighbor, internalChannel, profile, payload);
                    }

                    @Override
                    public boolean deliverNormalFrame(NodeId originalSource, String logicalChannel, ByteBuffer normalFrame) {
                        NostrRTCSocket socket = socketForNode(originalSource);
                        if (socket == null || socket.isClosed()) return false;
                        NostrRTCChannel channel = socket.getChannel(logicalChannel);
                        return channel != null && channel.onRoutedSocketMessage(normalFrame);
                    }

                    @Override
                    public void routingStateChanged() {
                        drainAllPendingSends();
                    }
                },
                routingTrafficLimiter
            );
        this.broadcastEngine =
            new BroadcastEngine(
                localNodeId,
                routingKeyPair,
                routingScope,
                new BroadcastContext() {
                    @Override
                    public NostrPublicKey routingPublicKey(NodeId origin, Instant now) {
                        return routingEngine.routingPublicKey(origin, now);
                    }

                    @Override
                    public TopologyGraph currentGraph() {
                        return routingGraph;
                    }

                    @Override
                    public TopologyGraph graphBySnapshotId(String snapshotId) {
                        synchronized (NostrRTCRoom.this) {
                            return recentRoutingGraphs.get(snapshotId);
                        }
                    }

                    @Override
                    public AsyncTask<Boolean> sendTreeEdge(
                        NodeId child,
                        RouteTransportProfile profile,
                        ByteBuffer encodedFrame
                    ) {
                        return sendInternalToNeighbor(child, InternalRoutingChannels.broadcast(profile), profile, encodedFrame);
                    }

                    @Override
                    public boolean deliverBroadcast(NodeId origin, String logicalChannel, ByteBuffer payload) {
                        NostrRTCSocket socket = socketForNode(origin);
                        if (socket == null || socket.isClosed()) return false;
                        NostrRTCChannel channel = socket.getChannel(logicalChannel);
                        if (channel == null) return false;
                        channel.onRoutedBroadcastMessage(payload);
                        return true;
                    }

                    @Override
                    public AsyncTask<Boolean> sendAck(BroadcastAck ack) {
                        return routingEngine.sendBroadcastAck(ack, Instant.now());
                    }

                    @Override
                    public AsyncTask<Boolean> repairUnicast(NodeId target, ByteBuffer encodedFrame) {
                        return routingEngine.sendBroadcastRepair(target, encodedFrame, Instant.now());
                    }
                },
                routingTrafficLimiter
            );
        this.routingEngine.setBroadcastHandlers(
                broadcastEngine::onAck,
                (source, frame) -> broadcastEngine.onRepairFrame(source, frame, Instant.now())
            );
    }

    private NostrRTCSocket newSocket(NostrRTCPeer remotePeer) {
        NostrRTCSocket socket = new NostrRTCSocket(executor, remotePeer, roomKeyPair, localPeer, settings, turnPool);
        socket.setPhysicalLinkCommitted(false);
        socket.setPhysicalLinkEnabled(false);
        socket.setForceTURN(forceTURN.get());
        socket.setRoutedTransport(routingEngine);
        return socket;
    }

    private NostrRTCSocket socketForNode(NodeId node) {
        for (Map.Entry<NostrRTCPeer, NostrRTCSocket> entry : connections.entrySet()) {
            NostrRTCPeer peer = entry.getKey();
            if (
                peer != null &&
                peer.getPubkey() != null &&
                node.equals(NodeId.derive(routingScope, peer.getPubkey(), peer.getSessionId()))
            ) {
                return entry.getValue();
            }
        }
        return null;
    }

    private AsyncTask<Boolean> sendInternalToNeighbor(
        NodeId neighbor,
        String internalChannel,
        RouteTransportProfile profile,
        ByteBuffer payload
    ) {
        NostrRTCSocket socket = socketForNode(neighbor);
        if (socket == null || socket.isClosed() || !physicalConnections.committed(socket.getRemotePeer())) {
            return AsyncTask.completed(Boolean.FALSE);
        }
        NostrRTCChannel channel = socket.createChannel(
            internalChannel,
            profile.isOrdered(),
            profile.isReliable(),
            profile.getMaxRetransmits(),
            profile.getMaxPacketLifeTime()
        );
        return channel.write(payload.asReadOnlyBuffer());
    }

    private void drainAllPendingSends() {
        for (NostrRTCChannel channel : pendingSends.keySet()) {
            drainQueue(channel);
        }
    }

    private NostrRTCSocket ensureLogicalSocket(NostrRTCPeer remotePeer) {
        if (remotePeer == null || remotePeer.getPubkey() == null) {
            return null;
        }
        if (
            localPeer.getPubkey().equals(remotePeer.getPubkey()) &&
            Objects.equals(localPeer.getSessionId(), remotePeer.getSessionId())
        ) {
            return null;
        }
        if (bannedPeers.contains(remotePeer.getPubkey())) {
            return null;
        }
        NostrRTCSocket existing = connections.get(remotePeer);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        NostrRTCSocket created;
        synchronized (this) {
            if (closed) return null;
            existing = connections.get(remotePeer);
            if (existing != null && !existing.isClosed()) return existing;
            if (connections.size() >= PhysicalConnectionManager.MAX_CANDIDATES) return null;
            if (existing != null) connections.remove(remotePeer, existing);
            created = newSocket(remotePeer);
            created.addInternalListener(listener);
            connections.put(remotePeer, created);
        }
        // Logical channels allocate no transport until capacity is reserved.
        created.createChannel(InternalRoutingChannels.CONTROL, true, true, null, null);
        onSocketAvailable(remotePeer, created);
        refreshDirectNeighbors();
        return created;
    }

    private void refreshDirectNeighbors() {
        if (closed) return;
        if (!directRefreshRunning.compareAndSet(false, true)) {
            scheduleTopologyRefresh();
            return;
        }
        try {
            refreshPhysicalConnections();
        } finally {
            directRefreshRunning.set(false);
        }
    }

    private void refreshPhysicalConnections() {
        Map<NodeId, NostrRTCSocket> socketsByNode = new HashMap<>();
        List<NodeId> membership = new ArrayList<>();
        List<NostrRTCPeer> peers = new ArrayList<>();
        membership.add(localNodeId);
        for (Map.Entry<NostrRTCPeer, NostrRTCSocket> entry : connections.entrySet()) {
            NostrRTCPeer peer = entry.getKey();
            NostrRTCSocket socket = entry.getValue();
            if (socket.isClosed() || isBannedPeer(peer.getPubkey())) continue;
            NodeId node = NodeId.derive(routingScope, peer.getPubkey(), peer.getSessionId());
            membership.add(node);
            peers.add(peer);
            socketsByNode.put(node, socket);
        }
        if (!physicalConnections.evaluate(peers)) {
            scheduleTopologyRefresh();
            return;
        }
        Instant now = Instant.now();
        Collection<NostrRTCConnectSignal> announces = signaling.getAnnounces();
        topologyControl.updatePresences(announces, now);
        List<NostrRTCPeer> routedPresences = new ArrayList<>();
        routedPresences.add(localPeer);
        for (NostrRTCConnectSignal announce : announces) {
            if (announce.supportsRouting() && !announce.isExpired(now)) routedPresences.add(announce.getPeer());
        }
        TopologyGraph graph = graphBuilder.build(routingScope, routedPresences, topologyControl.getSnapshots(now), now);
        String previousGraphId = routingGraph.getSnapshotId();
        synchronized (this) {
            if (closed) return;
            routingGraph = graph;
            topologyEvaluatedAt = now;
            recentRoutingGraphs.put(graph.getSnapshotId(), graph);
            while (recentRoutingGraphs.size() > 2) recentRoutingGraphs.remove(recentRoutingGraphs.keySet().iterator().next());
        }
        OverlayPlan plan = neighborManager.update(routingScope, membership, settings.getMaxDirectPeers(), graph, now);
        // A common two-neighbor ring is independent of the local K. Full-mesh
        // BACKBONE labels below the cap must not freeze every optional link.
        Set<NodeId> minimum = new BoundedOverlaySelector().select(routingScope, membership, 2).getNeighbors(localNodeId);
        Map<NostrRTCPeer, Integer> roles = new HashMap<>();
        Set<NostrRTCPeer> protectedPeers = new HashSet<>();
        for (DesiredDirectEdge edge : plan.getEdges()) {
            if (!edge.contains(localNodeId)) continue;
            NostrRTCSocket socket = socketsByNode.get(edge.other(localNodeId));
            if (socket == null) continue;
            int role = edge.getPriority() == OverlayEdgePriority.REPAIR
                ? 1
                : edge.getPriority() == OverlayEdgePriority.BACKBONE ? 0 : 2;
            if (physicalConnections.hasPriority() && role == 0 && !minimum.contains(edge.other(localNodeId))) role = 2;
            roles.put(socket.getRemotePeer(), role);
            if (role < 2) protectedPeers.add(socket.getRemotePeer());
        }
        Map<NostrRTCPeer, PhysicalConnectionManager.LinkState> links = new HashMap<>();
        for (Map.Entry<NodeId, NostrRTCSocket> entry : socketsByNode.entrySet()) {
            NostrRTCSocket socket = entry.getValue();
            NostrRTCPeer peer = socket.getRemotePeer();
            if (minimum.contains(entry.getKey())) {
                roles.put(peer, 0);
                protectedPeers.add(peer);
            }
            if (isBridge(graph, localNodeId, entry.getKey())) protectedPeers.add(peer);
            NostrRTCChannel channel = socket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME);
            links.put(
                peer,
                new PhysicalConnectionManager.LinkState(
                    socket.hasBidirectionalPhysicalTransport(),
                    channel != null && routingEngine.isRouteReady(channel),
                    socket.getActiveTransportPath().name(),
                    socket.getTurnPool() != null && socket.hasCompleteTurnConfiguration()
                )
            );
        }
        physicalConnections.reconcile(links, roles, protectedPeers);
        cleanupPhysicalConnections();
        if (started && signaling.isSignalingStarted()) {
            for (Attempt attempt : physicalConnections.fill()) beginPhysicalConnection(attempt);
        }
        if (signaling.isSignalingStarted()) advancePhysicalAdmissions();
        List<TopologyNeighbor> published = new ArrayList<>();
        for (Map.Entry<NodeId, NostrRTCSocket> entry : socketsByNode.entrySet()) {
            NostrRTCSocket socket = entry.getValue();
            socket.setPhysicalLinkCommitted(physicalConnections.committed(socket.getRemotePeer()));
            if (
                !physicalConnections.committed(socket.getRemotePeer()) || !supportsRouting(socket.getRemotePeer(), announces)
            ) continue;
            NostrRTCPeer peer = socket.getRemotePeer();
            published.add(
                new TopologyNeighbor(
                    entry.getKey(),
                    peer.getPubkey(),
                    peer.getSessionId(),
                    EdgeId.derive(routingScope, localNodeId, entry.getKey()),
                    topologyTransport(socket.getActiveTransportPath())
                )
            );
        }
        topologyControl.requestPublish(published);
        if (!previousGraphId.equals(graph.getSnapshotId())) drainAllPendingSends();
    }

    static boolean isBridge(TopologyGraph graph, NodeId local, NodeId neighbor) {
        if (graph.findEdge(local, neighbor) == null) return false;
        Set<NodeId> visited = new HashSet<>();
        java.util.ArrayDeque<NodeId> pending = new java.util.ArrayDeque<>();
        pending.add(local);
        while (!pending.isEmpty()) {
            NodeId node = pending.removeFirst();
            if (!visited.add(node)) continue;
            if (node.equals(neighbor)) return false;
            for (TopologyEdge edge : graph.getEdges(node)) {
                NodeId other = edge.other(node);
                if ((node.equals(local) && other.equals(neighbor)) || (node.equals(neighbor) && other.equals(local))) continue;
                pending.add(other);
            }
        }
        return true;
    }

    private static boolean supportsRouting(NostrRTCPeer peer, Collection<NostrRTCConnectSignal> announces) {
        for (NostrRTCConnectSignal announce : announces) {
            if (announce.supportsRouting() && announce.getPeer().equals(peer)) return true;
        }
        return false;
    }

    private static TopologyTransport topologyTransport(NostrRTCSocket.TransportPath path) {
        if (path == NostrRTCSocket.TransportPath.RTC) return TopologyTransport.RTC;
        if (path == NostrRTCSocket.TransportPath.TURN) return TopologyTransport.TURN;
        return TopologyTransport.UNKNOWN;
    }

    private static void ensureInternalProfileChannels(NostrRTCSocket socket, RouteTransportProfile profile) {
        socket.createChannel(
            InternalRoutingChannels.data(profile),
            profile.isOrdered(),
            profile.isReliable(),
            profile.getMaxRetransmits(),
            profile.getMaxPacketLifeTime()
        );
        socket.createChannel(
            InternalRoutingChannels.broadcast(profile),
            profile.isOrdered(),
            profile.isReliable(),
            profile.getMaxRetransmits(),
            profile.getMaxPacketLifeTime()
        );
    }

    private void scheduleTopologyRefresh() {
        synchronized (this) {
            if (closed || topologyRefreshScheduled) return;
            topologyRefreshScheduled = true;
        }
        executor.runLater(
            () -> {
                synchronized (NostrRTCRoom.this) {
                    topologyRefreshScheduled = false;
                }
                refreshDirectNeighbors();
                return null;
            },
            50L,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        physicalConnections.close();
        cleanupPhysicalConnections();
        try {
            this.broadcastEngine.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error closing broadcast transport", e);
        }
        try {
            this.routingEngine.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error closing routed transport", e);
        }
        try {
            this.topologyControl.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error closing topology control plane", e);
        }
        try {
            this.routingKeyPair.destroy();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error destroying ephemeral routing key", e);
        }
        // close everything
        for (NostrRTCSocket socket : connections.values()) {
            try {
                socket.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing socket", e);
            }
        }
        connections.clear();
        for (BlockingPacketQueue<NostrRTCChannel.PreparedPacket> queue : pendingSends.values()) {
            try {
                queue.close();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error closing pending send queue", e);
            }
        }
        pendingSends.clear();
        synchronized (this) {
            recentRoutingGraphs.clear();
        }
        routingGraph = new TopologyGraph(Collections.emptySet(), Collections.emptySet());
        bannedPeers.clear();
        onSocketAvailable.clear();
        onDisconnectionListeners.clear();
        onMessageListeners.clear();
        onPeerDiscoveredListeners.clear();
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
        this.forceTURN.set(forceTURN);
        for (NostrRTCSocket socket : connections.values()) {
            socket.setForceTURN(forceTURN);
        }
    }

    public boolean isForceTURN() {
        return forceTURN.get();
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

    private void onSocketAvailable(NostrRTCPeer peer, NostrRTCSocket socket) {
        socket.createChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME);
        for (NostrRTCPeerSocketAvailableListener listener : onSocketAvailable) {
            try {
                listener.onRoomPeerSocketAvailable(peer, socket);
            } catch (Throwable e) {
                logger.log(Level.WARNING, "Error notifying listener", e);
            }
        }
    }

    private void loop() {
        if (closed) return;
        executor.runLater(
            () -> {
                if (closed) return null;
                try {
                    for (NostrRTCConnectSignal announce : signaling.getAnnounces()) ensureLogicalSocket(announce.getPeer());
                    refreshDirectNeighbors();
                } catch (Throwable error) {
                    logger.log(Level.WARNING, "RTC room maintenance failed", error);
                }
                if (!closed) loop();
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
        if (isBannedPeer(pubkey)) {
            logger.fine("Not offering connection to banned peer: " + pubkey);
            return false;
        }

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

    static boolean shouldDeferRtcAttempt(NostrRTCSocket socket) {
        if (socket == null) {
            return false;
        }
        if (socket.hasUsableTransport()) {
            return !socket.shouldAttemptRtcUpgrade();
        }
        // Give the TURN path enabled by a failed RTC attempt time to establish.
        // Re-entering prepareRtcTransportAttempt() here clears the fallback flag
        // before the logical channels can bootstrap their TURN connections.
        return socket.isPendingConnection() || socket.isTurnFallbackAllowed();
    }

    public AsyncTask<Void> discover() {
        return this.signaling.start(false);
    }

    public AsyncTask<Void> start() {
        if (closed) return AsyncTask.failed(new IllegalStateException("Room is closed"));
        if (!started) {
            started = true;
            this.topologyControl.start();
            this.loop();
        }
        return this.signaling.start(true);
    }

    /**
     * @deprecated Use {@link #disconnect(NostrRTCPeer)} instead.
     */
    public void kick(NostrRTCPeer peer) {
        disconnect(peer);
    }

    /**
     * @deprecated Use {@link #kick(NostrPublicKey)} instead.
     */
    public void kick(NostrPublicKey peer) {
        disconnect(peer);
    }

    /**
     * Disconnect all peers associated with a pubkey
     * @param peer the peer to disconnect
     */
    public void disconnect(NostrPublicKey peer) {
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
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Exception in listener", e);
                }
            }
        }
        refreshDirectNeighbors();
    }

    /**
     * Disconnect a peer
     * @param peer
     */
    public void disconnect(NostrRTCPeer peer) {
        NostrRTCSocket socket = connections.remove(peer);
        if (socket != null) {
            socket.close();
            for (NostrRTCRoomPeerDisconnectListener listener : onDisconnectionListeners) {
                try {
                    listener.onRoomPeerDisconnected(peer, socket);
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Exception in listener", e);
                }
            }
            refreshDirectNeighbors();
        }
    }

    private void onRTCSocketClose(NostrRTCSocket socket) {
        // if the socket is closed remotely, we remove it from the list of connections
        // and notify the listeners
        NostrRTCPeer remotePeer = socket.getRemotePeer();
        if (remotePeer == null || remotePeer.getPubkey() == null) return;
        NostrRTCSocket current = connections.get(remotePeer);
        if (current != socket) return;
        boolean removed = connections.remove(remotePeer, socket);
        if (removed) {
            logger.fine("Closed peer: " + remotePeer);
            for (NostrRTCRoomPeerDisconnectListener listener : onDisconnectionListeners) {
                try {
                    listener.onRoomPeerDisconnected(remotePeer, socket);
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Exception in listener", e);
                }
            }
            refreshDirectNeighbors();
        }
    }

    /**
     * Ban a pubkey. Peers with the same pubkey will be disconnected and won't be able to reconnect until unbanned or the room is restarted.
     * @param peer the peer to ban
     */
    public void ban(NostrPublicKey peer) {
        synchronized (this) {
            if (!bannedPeers.contains(peer)) {
                logger.fine("Banning peer: " + peer);
                bannedPeers.add(peer);
            } else {
                logger.fine("Peer already banned: " + peer);
            }
        }
        kick(peer);
    }

    /**
     * Unban a peer. The peer can reconnect immediately.
     * @param peer the peer to unban
     */
    public void unban(NostrPublicKey peer) {
        synchronized (this) {
            logger.fine("Unbanning peer: " + peer);
            bannedPeers.remove(peer);
        }
    }

    private boolean isBannedPeer(NostrPublicKey peer) {
        return peer != null && bannedPeers.contains(peer);
    }

    private void onAddAnnounce(NostrRTCConnectSignal announce) {
        mergeSocketAdvertisedVersion(announce);
        ensureLogicalSocket(announce.getPeer());
        for (NostrRTCRoomPeerDiscoveredListener listener : onPeerDiscoveredListeners) {
            try {
                listener.onRoomPeerDiscovered(
                    announce.getPeer(),
                    announce,
                    NostrRTCRoomPeerDiscoveredListener.NostrRTCRoomPeerDiscoveredState.ONLINE
                );
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    private void onUpdateAnnounce(NostrRTCConnectSignal announce) {
        mergeSocketAdvertisedVersion(announce);
        ensureLogicalSocket(announce.getPeer());
        for (NostrRTCRoomPeerDiscoveredListener listener : onPeerDiscoveredListeners) {
            try {
                listener.onRoomPeerDiscovered(
                    announce.getPeer(),
                    announce,
                    NostrRTCRoomPeerDiscoveredListener.NostrRTCRoomPeerDiscoveredState.ONLINE
                );
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }
    }

    private void mergeSocketAdvertisedVersion(NostrRTCConnectSignal announce) {
        NostrRTCSocket socket = connections.get(announce.getPeer());
        if (socket != null && socket.getRemotePeer() != null) {
            socket.getRemotePeer().mergeAuthenticatedAnnouncement(announce.getPeer());
        }
    }

    private void onRemoveAnnounce(NostrRTCConnectSignal announce, NostrRTCSignaling.Listener.RemoveReason reason) {
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
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Exception in listener", e);
            }
        }

        NostrRTCSocket socket = connections.get(remotePeer);
        if (socket != null) {
            socket.close();
            onRTCSocketClose(socket);
        }
    }

    /**
     * Get some info about the local peer
     * @return the local peer info
     */
    public NostrRTCPeer getLocalPeerInfo() {
        return this.localPeer;
    }

    /**
     * Return a snapshot of all currently announced logical remote peers.
     */
    public Set<NostrRTCPeer> getPeers() {
        return Collections.unmodifiableSet(new HashSet<NostrRTCPeer>(connections.keySet()));
    }

    /**
     * Resolve the normal logical socket for an announced peer.
     */
    @Nullable
    public NostrRTCSocket getSocket(NostrRTCPeer peer) {
        return connections.get(peer);
    }

    /**
     * Return a snapshot of all normal logical peer sockets.
     */
    public Collection<NostrRTCSocket> getSockets() {
        return Collections.unmodifiableList(new ArrayList<NostrRTCSocket>(connections.values()));
    }

    /**
     * Return the immutable, mutually attested topology currently used for
     * routed sends and tree broadcasts.
     *
     * @return the current local routing topology snapshot
     */
    TopologyGraph getRoutingTopology() {
        return routingGraph;
    }

    /**
     * Return the valid private topology snapshots currently known by this room.
     *
     * @return an immutable snapshot collection
     */
    Collection<TopologySnapshot> getRoutingTopologySnapshots() {
        return Collections.unmodifiableList(new ArrayList<TopologySnapshot>(topologyControl.getSnapshots(Instant.now())));
    }

    /**
     * Replace the local physical-neighbor preference and reevaluate known peers.
     * Null disables preference-driven swaps. Zero is admitted; a negative value
     * retires physical resources while preserving the logical endpoint and routing
     * metadata. Invalid results retain the peer's last valid evaluation.
     */
    public void setDiscoveryPriority(DiscoveryPriority priority) {
        physicalConnections.setPriority(priority);
        scheduleTopologyRefresh();
    }

    /** Bounded read-only snapshot of physical reservations, retry state and attested routing topology. */
    public RTCConnectionDiagnostics getConnectionDiagnostics() {
        return physicalConnections.snapshot(routingGraph, topologyEvaluatedAt, topologyControl.getSnapshots(Instant.now()));
    }

    private boolean isAttemptCurrent(Attempt a) {
        NostrRTCSocket socket = connections.get(a.peer);
        return (
            !closed &&
            !isBannedPeer(a.peer.getPubkey()) &&
            socket != null &&
            !socket.isClosed() &&
            physicalConnections.active(a)
        );
    }

    private void cleanupPhysicalConnections() {
        for (Attempt a : physicalConnections.takeClosures()) {
            NostrRTCSocket socket = connections.get(a.peer);
            if (
                a.admission &&
                a.phase != Phase.ESTABLISHED &&
                socket != null &&
                !closed &&
                physicalConnections.responseAllowed()
            ) {
                NostrRTCLinkSignal abort = new NostrRTCLinkSignal(
                    localPeer.getSigner(),
                    roomKeyPair,
                    localPeer,
                    NostrRTCLinkSignal.Command.ABORT,
                    a.id,
                    a.peer.getSessionId()
                );
                signaling
                    .sendBoundSignal(abort, a.peer.getPubkey(), () -> !closed && connections.get(a.peer) == socket)
                    .catchException(error -> {});
            }
            if (socket != null) socket.setPhysicalLinkEnabled(false);
            physicalConnections.released(a);
        }
    }

    private NostrRTCSocket preparePhysicalConnection(Attempt a) {
        NostrRTCSocket socket = connections.get(a.peer);
        if (socket == null || !isAttemptCurrent(a)) return null;
        if (!socket.enablePhysicalAttempt(() -> isAttemptCurrent(a))) return null;
        socket.createChannel(InternalRoutingChannels.CONTROL, true, true, null, null);
        ensureInternalProfileChannels(socket, RouteTransportProfile.RELIABLE_ORDERED);
        ensureInternalProfileChannels(socket, RouteTransportProfile.UNRELIABLE_UNORDERED);
        if (a.admission) socket.createChannel(InternalRoutingChannels.LINK_ADMISSION, true, true, null, null);
        return socket;
    }

    private void beginPhysicalConnection(Attempt a) {
        try {
            NostrRTCSocket socket = preparePhysicalConnection(a);
            if (socket == null) {
                physicalConnections.fail(a, "socket-unavailable");
                return;
            }
            if (a.admission) {
                a.turnOnly = forceTURN.get();
                sendAdmissionSignal(
                    a,
                    a.turnOnly ? NostrRTCLinkSignal.Command.TURN_REQUEST : NostrRTCLinkSignal.Command.REQUEST
                );
            } else {
                createPhysicalOffer(a, socket);
            }
        } catch (Throwable error) {
            physicalConnections.fail(a, "attempt-start-failed");
        }
        scheduleTopologyRefresh();
    }

    private void createPhysicalOffer(Attempt a, NostrRTCSocket socket) {
        if (!isAttemptCurrent(a)) return;
        try {
            socket.prepareRtcTransportAttempt(() -> isAttemptCurrent(a));
            socket
                .listen(() -> isAttemptCurrent(a))
                .then(offer -> {
                    refreshDirectNeighbors();
                    if (isAttemptCurrent(a)) sendAttemptSignal(a, offer);
                    return null;
                })
                .catchException(error -> {
                    physicalConnections.fail(a, "offer-failed");
                    scheduleTopologyRefresh();
                });
        } catch (Throwable error) {
            physicalConnections.fail(a, "offer-failed");
            scheduleTopologyRefresh();
        }
    }

    private void sendAttemptSignal(Attempt a, NostrRTCSignal signal) {
        if (a.admission) signal.withLinkAttempt(a.id, a.peer.getSessionId());
        signaling
            .sendBoundSignal(signal, a.peer.getPubkey(), () -> isAttemptCurrent(a))
            .catchException(error -> {
                physicalConnections.fail(a, "signaling-failed");
                scheduleTopologyRefresh();
            });
    }

    private void sendAdmissionSignal(Attempt a, NostrRTCLinkSignal.Command command) {
        if (!isAttemptCurrent(a)) return;
        sendAttemptSignal(
            a,
            new NostrRTCLinkSignal(localPeer.getSigner(), roomKeyPair, localPeer, command, a.id, a.peer.getSessionId())
        );
    }

    private void rejectAdmission(NostrRTCLinkSignal request) {
        if (closed || !physicalConnections.responseAllowed()) return;
        NostrRTCLinkSignal busy = new NostrRTCLinkSignal(
            localPeer.getSigner(),
            roomKeyPair,
            localPeer,
            NostrRTCLinkSignal.Command.BUSY,
            request.getLinkAttemptId(),
            request.getPeer().getSessionId()
        );
        signaling.sendBoundSignal(busy, request.getPeer().getPubkey(), () -> !closed).catchException(error -> {});
    }

    private void onReceiveLinkSignal(NostrRTCLinkSignal signal) {
        if (
            closed ||
            !signaling.isSignalingStarted() ||
            !localPeer.getSessionId().equals(signal.getTargetSession()) ||
            isBannedPeer(signal.getPeer().getPubkey())
        ) return;
        NostrRTCSocket socket = connections.get(signal.getPeer());
        if (socket == null || !socket.getRemotePeer().supportsLinkAdmission()) return;
        refreshDirectNeighbors();
        NostrRTCLinkSignal.Command command = signal.getCommand();
        Attempt a = physicalConnections.attempt(signal.getPeer());
        if (command == NostrRTCLinkSignal.Command.REQUEST || command == NostrRTCLinkSignal.Command.TURN_REQUEST) {
            if (a != null) {
                if (a.id.equals(signal.getLinkAttemptId()) && !a.outgoing) {
                    if (physicalConnections.controlDue(a)) sendAdmissionSignal(
                        a,
                        a.turnOnly ? NostrRTCLinkSignal.Command.TURN_ACCEPT : NostrRTCLinkSignal.Command.ACCEPT
                    );
                    return;
                }
                // Resolve simultaneous intents before either side starts ICE.
                if (a.outgoing && a.phase == Phase.REQUESTED) {
                    if (localPeer.getPubkey().asHex().compareTo(signal.getPeer().getPubkey().asHex()) > 0) {
                        physicalConnections.abort(a, "intent-collision");
                        cleanupPhysicalConnections();
                    } else {
                        // The preferred request will make the other endpoint withdraw.
                        // BUSY here can arrive first and put both simultaneous intents
                        // into backoff before the winning request is admitted.
                        return;
                    }
                } else {
                    rejectAdmission(signal);
                    return;
                }
            }
            // Rejecting another request solely for our short backoff can put
            // both endpoints into alternating BUSY/backoff indefinitely. Its
            // bounded retransmission waits for eligibility without starting ICE.
            if (physicalConnections.coolingDown(socket.getRemotePeer())) return;
            a = physicalConnections.admit(socket.getRemotePeer(), signal.getLinkAttemptId(), true);
            if (a == null) {
                rejectAdmission(signal);
                return;
            }
            a.turnOnly = forceTURN.get() || command == NostrRTCLinkSignal.Command.TURN_REQUEST;
            try {
                preparePhysicalConnection(a);
                sendAdmissionSignal(a, a.turnOnly ? NostrRTCLinkSignal.Command.TURN_ACCEPT : NostrRTCLinkSignal.Command.ACCEPT);
                if (a.turnOnly) activateAttemptTurn(a, socket);
            } catch (Throwable error) {
                physicalConnections.fail(a, "inbound-start-failed");
            }
        } else {
            if (a == null || !a.id.equals(signal.getLinkAttemptId()) || !isAttemptCurrent(a)) return;
            switch (command) {
                case ACCEPT:
                case TURN_ACCEPT:
                    if (!a.outgoing || !physicalConnections.accepted(a)) return;
                    a.turnOnly = a.turnOnly || command == NostrRTCLinkSignal.Command.TURN_ACCEPT;
                    if (a.turnOnly) activateAttemptTurn(a, socket); else createPhysicalOffer(a, socket);
                    break;
                case BUSY:
                case ABORT:
                    if (a.phase != Phase.ESTABLISHED) physicalConnections.fail(
                        a,
                        command == NostrRTCLinkSignal.Command.BUSY ? "remote-busy" : "remote-abort"
                    );
                    break;
                default:
                    break;
            }
        }
        scheduleTopologyRefresh();
    }

    private void activateAttemptTurn(Attempt a, NostrRTCSocket socket) {
        socket.activatePhysicalTurnFallback();
        sendAttemptSignal(
            a,
            new NostrRTCRouteSignal(
                localPeer.getSigner(),
                roomKeyPair,
                localPeer,
                Collections.emptyList(),
                socket.resolveReceiveTurnUrl()
            )
        );
    }

    private boolean matchesAttempt(NostrRTCSignal signal, Attempt a) {
        if (a == null || !isAttemptCurrent(a)) return false;
        if (a.admission) return (
            a.id.equals(signal.getLinkAttemptId()) && localPeer.getSessionId().equals(signal.getTargetSession())
        );
        return signal.getLinkAttemptId() == null;
    }

    private void onReceiveOffer(NostrRTCOfferSignal offer) {
        if (closed || isBannedPeer(offer.getPeer().getPubkey())) return;
        NostrRTCSocket socket = connections.get(offer.getPeer());
        if (socket == null) socket = ensureLogicalSocket(offer.getPeer());
        if (socket == null) return;
        refreshDirectNeighbors();
        Attempt a = physicalConnections.attempt(socket.getRemotePeer());
        if (!socket.getRemotePeer().supportsLinkAdmission()) {
            if (a != null) {
                if (!a.outgoing || shouldOfferConnection(offer.getPeer().getPubkey())) return;
                physicalConnections.abort(a, "legacy-offer-collision");
                cleanupPhysicalConnections();
            }
            a =
                physicalConnections.admit(
                    socket.getRemotePeer(),
                    NGEUtils.bytesToHex(NGEPlatform.get().randomBytes(16)),
                    false
                );
            if (a == null) return;
            preparePhysicalConnection(a);
        }
        if (!matchesAttempt(offer, a) || a.outgoing || a.phase != Phase.ACCEPTED || socket.isRTCConnected()) return;
        final Attempt current = a;
        if (!physicalConnections.claimDescription(a, true)) return;
        try {
            socket
                .connect(offer, () -> isAttemptCurrent(current))
                .then(answer -> {
                    refreshDirectNeighbors();
                    if (answer != null && isAttemptCurrent(current)) sendAttemptSignal(current, answer);
                    return null;
                })
                .catchException(error -> {
                    physicalConnections.fail(current, "answer-failed");
                    scheduleTopologyRefresh();
                });
        } catch (Throwable error) {
            physicalConnections.fail(current, "inbound-offer-failed");
            scheduleTopologyRefresh();
        }
    }

    private void onReceiveAnswer(NostrRTCAnswerSignal answer) {
        refreshDirectNeighbors();
        Attempt a = physicalConnections.attempt(answer.getPeer());
        if (!matchesAttempt(answer, a) || !a.outgoing) return;
        NostrRTCSocket socket = connections.get(a.peer);
        if (!socket.isPendingConnection() || !physicalConnections.claimDescription(a, false)) return;
        try {
            socket
                .connect(answer, () -> isAttemptCurrent(a))
                .catchException(error -> {
                    physicalConnections.fail(a, "connect-failed");
                    scheduleTopologyRefresh();
                });
        } catch (Throwable error) {
            physicalConnections.fail(a, "connect-failed");
            scheduleTopologyRefresh();
        }
    }

    private void onReceiveCandidates(NostrRTCRouteSignal candidate) {
        if (closed || isBannedPeer(candidate.getPeer().getPubkey())) return;
        Attempt a = physicalConnections.attempt(candidate.getPeer());
        if (!matchesAttempt(candidate, a)) return;
        NostrRTCSocket socket = connections.get(a.peer);
        socket.mergeRemoteRTCIceCandidate(candidate, () -> isAttemptCurrent(a));
        scheduleTopologyRefresh();
    }

    private void onRTCSocketLocalIceCandidate(
        NostrRTCSocket socket,
        Collection<RTCTransportIceCandidate> candidates,
        String turn,
        long transportGeneration
    ) {
        Attempt a = physicalConnections.attempt(socket.getRemotePeer());
        if (a == null || !isAttemptCurrent(a) || socket.getRtcTransportGeneration() != transportGeneration) return;
        try {
            NostrRTCRouteSignal signal = new NostrRTCRouteSignal(
                localPeer.getSigner(),
                roomKeyPair,
                localPeer,
                candidates,
                turn
            );
            if (a.admission) signal.withLinkAttempt(a.id, a.peer.getSessionId());
            signaling
                .sendBoundSignal(
                    signal,
                    a.peer.getPubkey(),
                    () -> isAttemptCurrent(a) && socket.getRtcTransportGeneration() == transportGeneration
                )
                .catchException(error -> {
                    if (
                        isAttemptCurrent(a) && socket.getRtcTransportGeneration() == transportGeneration
                    ) physicalConnections.fail(a, "candidate-send-failed");
                    scheduleTopologyRefresh();
                });
        } catch (Throwable error) {
            physicalConnections.fail(a, "candidate-send-failed");
            scheduleTopologyRefresh();
        }
    }

    private void advancePhysicalAdmissions() {
        for (Attempt a : physicalConnections.pending()) {
            if (!a.admission || !isAttemptCurrent(a)) continue;
            if (a.phase == Phase.REQUESTED) {
                if (physicalConnections.controlDue(a)) sendAdmissionSignal(
                    a,
                    a.turnOnly ? NostrRTCLinkSignal.Command.TURN_REQUEST : NostrRTCLinkSignal.Command.REQUEST
                );
                continue;
            }
            NostrRTCSocket socket = connections.get(a.peer);
            NostrRTCChannel channel = socket.getChannel(InternalRoutingChannels.LINK_ADMISSION);
            if (channel == null || !channel.isPhysicalReady()) continue;
            physicalConnections.ready(a, false);
            if (!physicalConnections.controlDue(a)) continue;
            if (a.outgoing && a.phase == Phase.READY) {
                if (physicalConnections.prepareCommit(a)) sendPhysicalAdmissionFrame(
                    a,
                    "COMMIT"
                ); else physicalConnections.fail(a, "commit-no-longer-admissible");
            } else if (a.phase == Phase.COMMIT_SENT) sendPhysicalAdmissionFrame(a, "COMMIT"); else if (
                a.phase == Phase.COMMIT_ACKED
            ) sendPhysicalAdmissionFrame(a, "COMMITTED"); else sendPhysicalAdmissionFrame(a, "READY");
        }
    }

    private void sendPhysicalAdmissionFrame(Attempt a, String command) {
        if (!isAttemptCurrent(a)) return;
        NostrRTCChannel channel = connections.get(a.peer).getChannel(InternalRoutingChannels.LINK_ADMISSION);
        if (channel == null || !channel.isPhysicalReady()) return;
        String payload = command + ":" + a.id;
        if ("READY".equals(command)) payload += ":" + a.challenge;
        if ("COMMIT".equals(command) || "COMMITTED".equals(command)) {
            if (a.remoteChallenge == null) return;
            payload += ":" + a.remoteChallenge + ":" + a.challenge;
        }
        ByteBuffer frame = ByteBuffer.wrap(payload.getBytes(StandardCharsets.US_ASCII));
        channel
            .write(channel.prepareOutgoingPacket(frame), () -> isAttemptCurrent(a) && channel.isPhysicalReady())
            .catchException(error -> {
                physicalConnections.fail(a, "direct-admission-failed");
                scheduleTopologyRefresh();
            });
    }

    private void onPhysicalAdmissionFrame(NostrRTCSocket socket, ByteBuffer frame) {
        if (frame.remaining() > 96 || !physicalConnections.responseAllowed()) return;
        byte[] bytes = new byte[frame.remaining()];
        frame.duplicate().get(bytes);
        String value = new String(bytes, StandardCharsets.US_ASCII);
        String[] fields = value.split(":", -1);
        if (fields.length < 2) return;
        Attempt a = physicalConnections.attempt(socket.getRemotePeer());
        if (a == null || !a.admission || !a.id.equals(fields[1]) || !isAttemptCurrent(a)) return;
        if ("FINAL".equals(fields[0]) && fields.length != 2) return;
        boolean ready = "READY".equals(fields[0]);
        boolean commit = "COMMIT".equals(fields[0]) || "COMMITTED".equals(fields[0]);
        if (ready && (fields.length != 3 || !fields[2].matches("[0-9a-f]{16}"))) return;
        if (commit && (fields.length != 4 || !a.challenge.equals(fields[2]) || !fields[3].matches("[0-9a-f]{16}"))) return;
        if (ready || commit) {
            String remoteChallenge = fields[ready ? 2 : 3];
            if (a.remoteChallenge != null && !a.remoteChallenge.equals(remoteChallenge)) return;
            a.remoteChallenge = remoteChallenge;
        }
        refreshDirectNeighbors();
        if (commit) {
            // The echoed nonce proves that our direct READY reached the peer,
            // even if its earlier READY frame was lost during channel startup.
            physicalConnections.ready(a, true);
            NostrRTCChannel channel = socket.getChannel(InternalRoutingChannels.LINK_ADMISSION);
            if (channel != null && channel.isPhysicalReady()) physicalConnections.ready(a, false);
        }
        switch (fields[0]) {
            case "READY":
                physicalConnections.ready(a, true);
                break;
            case "COMMIT":
                if (
                    !a.outgoing && a.phase != Phase.ESTABLISHED && physicalConnections.prepareCommit(a)
                ) sendPhysicalAdmissionFrame(a, "COMMITTED");
                break;
            case "COMMITTED":
                if (
                    a.outgoing &&
                    (a.phase == Phase.ESTABLISHED || (a.phase == Phase.COMMIT_SENT && physicalConnections.commit(a)))
                ) {
                    sendPhysicalAdmissionFrame(a, "FINAL");
                    cleanupPhysicalConnections();
                } else if (a.outgoing) physicalConnections.fail(a, "commit-aborted");
                break;
            case "FINAL":
                if (!a.outgoing && a.phase == Phase.COMMIT_ACKED) {
                    if (!physicalConnections.commit(a)) physicalConnections.fail(a, "commit-aborted");
                    cleanupPhysicalConnections();
                }
                break;
            default:
                return;
        }
        scheduleTopologyRefresh();
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
        requireApplicationChannelName(channel);
        NostrRTCSocket socket = connections.get(peer);
        if (socket == null) {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
        NostrRTCChannel chan = socket.getChannel(channel);
        if (chan == null) {
            throw new IllegalStateException(
                "No channel named " + channel + " found for peer: " + peer + " use createChannel method to create it first"
            );
        }
        BlockingPacketQueue<NostrRTCChannel.PreparedPacket> q = pendingSends.computeIfAbsent(
            chan,
            ignored -> newPendingSendQueue(chan)
        );
        return NGEUtils
            .getPlatform()
            .wrapPromise((rs, rj) -> {
                q.enqueue(chan.prepareOutgoingPacket(bbf), rs, rj);
                drainQueue(chan);
            });
    }

    public AsyncTask<Void> send(NostrRTCChannel chan, ByteBuffer bbf) {
        requireApplicationChannelName(chan.getName());
        NostrRTCPeer peer = chan.getSocket().getRemotePeer();
        NostrRTCSocket socket = connections.get(peer);
        if (socket == null) {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
        BlockingPacketQueue<NostrRTCChannel.PreparedPacket> q = pendingSends.computeIfAbsent(
            chan,
            ignored -> newPendingSendQueue(chan)
        );
        return NGEUtils
            .getPlatform()
            .wrapPromise((rs, rj) -> {
                q.enqueue(chan.prepareOutgoingPacket(bbf), rs, rj);
                drainQueue(chan);
            });
    }

    // public NostrRTCChannel getChannel(NostrRTCPeer peer, String channel) {
    //     // NostrRTCSocket socket = connections.get(peer);
    //     // if (socket != null) {
    //     //     return socket.getChannel(channel);
    //     // } else {
    //     //     logger.warning("No socket found for peer: " + peer);
    //     //     throw new IllegalStateException("No socket found for peer: " + peer);
    //     // }
    //     return createChannel(peer, channel);
    // }

    public NostrRTCChannel createChannel(NostrRTCPeer peer, String channel) {
        requireApplicationChannelName(channel);
        NostrRTCSocket socket = connections.get(peer);
        if (socket != null) {
            return socket.createChannel(channel);
        } else {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
    }

    public NostrRTCChannel createChannel(NostrRTCPeer peer, String channel, boolean ordered, boolean reliable) {
        return createChannel(peer, channel, ordered, reliable, null, null);
    }

    public NostrRTCChannel createChannel(
        NostrRTCPeer peer,
        String channel,
        boolean ordered,
        boolean reliable,
        @Nullable Integer maxRetransmits,
        @Nullable Duration maxPacketLifeTime
    ) {
        requireApplicationChannelName(channel);
        NostrRTCSocket socket = connections.get(peer);
        if (socket != null) {
            NostrRTCChannel created = socket.createChannel(channel, ordered, reliable, maxRetransmits, maxPacketLifeTime);
            RouteTransportProfile profile = new RouteTransportProfile(
                ordered,
                reliable,
                maxPacketLifeTime == null ? maxRetransmits : null,
                maxPacketLifeTime
            );
            for (NostrRTCSocket direct : connections.values()) {
                if (direct.isPhysicalLinkEnabled()) ensureInternalProfileChannels(direct, profile);
            }
            return created;
        } else {
            logger.warning("No socket found for peer: " + peer);
            throw new IllegalStateException("No socket found for peer: " + peer);
        }
    }

    private static void requireApplicationChannelName(String channel) {
        if (InternalRoutingChannels.isReserved(channel)) {
            throw new IllegalArgumentException("Channel label is reserved for internal NIP-DC routing");
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
        requireApplicationChannelName(channel);
        if (connections.isEmpty()) return AsyncTask.completed(null);
        TopologyGraph graph = routingGraph;
        Set<NodeId> logicalMembership = new HashSet<NodeId>();
        logicalMembership.add(localNodeId);
        for (NostrRTCPeer peer : connections.keySet()) {
            logicalMembership.add(NodeId.derive(routingScope, peer.getPubkey(), peer.getSessionId()));
        }
        if (graph.getNodes().equals(logicalMembership) && graph.connectedComponents().size() == 1) {
            NostrRTCChannel sample = null;
            for (NostrRTCSocket socket : connections.values()) {
                sample = socket.getChannel(channel);
                if (sample != null) break;
            }
            if (sample == null) {
                return AsyncTask.failed(new IllegalStateException("No channel named " + channel + " is available"));
            }
            RouteTransportProfile profile = RouteTransportProfile.fromChannel(
                sample.isOrdered(),
                sample.isReliable(),
                sample.getMaxRetransmits(),
                sample.getMaxPacketLifeTime()
            );
            return broadcastEngine.broadcast(channel, profile, bbf.asReadOnlyBuffer(), Instant.now()).then(ignored -> null);
        }
        if (connections.size() > settings.getMaxDirectPeers()) {
            return AsyncTask.failed(new IllegalStateException("No connected mutually attested graph for broadcast"));
        }
        ArrayList<AsyncTask<Void>> tasks = new ArrayList<>(connections.size());
        for (Map.Entry<NostrRTCPeer, NostrRTCSocket> entry : connections.entrySet()) {
            NostrRTCSocket socket = entry.getValue();
            if (socket == null) {
                continue;
            }
            NostrRTCChannel chan = socket.getChannel(channel);
            if (chan == null) {
                logger.fine("Skipping broadcast to peer without channel " + channel + ": " + entry.getKey());
                continue;
            }
            if (!chan.isReady()) {
                logger.fine("Skipping broadcast to peer with unready channel " + channel + ": " + entry.getKey());
                continue;
            }
            tasks.add(send(chan, bbf));
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
    // private List<NostrRTCSocket> removeSocketsForPeer(NostrRTCPeer peer) {
    //     List<NostrRTCSocket> removed = new ArrayList<>();
    //     for (Map.Entry<NostrRTCPeer, NostrRTCSocket> entry : new ArrayList<>(connections.entrySet())) {
    //         NostrRTCPeer key = entry.getKey();
    //         if (key == null || !key.equals(peer)) continue;
    //         if (connections.remove(key, entry.getValue())) {
    //             removed.add(entry.getValue());
    //         }
    //     }
    //     return removed;
    // }
}
