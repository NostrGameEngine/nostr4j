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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
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
import org.ngengine.nostr4j.rtc.routing.topology.DirectNeighborManager;
import org.ngengine.nostr4j.rtc.routing.topology.MutualTopologyGraphBuilder;
import org.ngengine.nostr4j.rtc.routing.topology.OverlayPlan;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyControlPlane;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyNeighbor;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.nostr4j.rtc.signal.NostrRTCAnswerSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCConnectSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCOfferSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCRouteSignal;
import org.ngengine.nostr4j.rtc.signal.NostrRTCSignaling;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
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
    private final AsyncExecutor executor;
    private final NostrKeyPair roomKeyPair;
    private final String turnServerUrl;
    private final NostrTURNPool turnPool;
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
    private volatile boolean forceTURN = false;

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
                @Override
                public AsyncTask<Boolean> handle(NostrRTCChannel.PreparedPacket packet) {
                    return chan.write(packet);
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
            getQueuedSendTimeoutMs()
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
        this.routingScope =
            new RoutingScope(roomKeyPair.getPublicKey(), localPeer.getProtocolId(), localPeer.getApplicationId());
        this.localNodeId = NodeId.derive(routingScope, localPeer.getPubkey(), localPeer.getSessionId());
        this.routingKeyPair = new NostrKeyPair();
        NostrPool checkedPool = Objects.requireNonNull(signalingPool, "Signaling pool cannot be null");
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
                            socket.isPhysicalLinkEnabled() &&
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
                new BroadcastContext() {
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
                frame -> broadcastEngine.onRepairFrame(frame, Instant.now())
            );
    }

    private NostrRTCSocket newSocket(NostrRTCPeer remotePeer) {
        NostrRTCSocket socket = new NostrRTCSocket(
            executor,
            remotePeer,
            roomKeyPair,
            localPeer,
            settings,
            turnServerUrl,
            turnPool
        );
        socket.setForceTURN(forceTURN);
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
        if (socket == null || socket.isClosed() || !socket.isPhysicalLinkEnabled()) {
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
        synchronized (this) {
            existing = connections.get(remotePeer);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            if (existing != null) {
                connections.remove(remotePeer, existing);
            }
            NostrRTCSocket created = newSocket(remotePeer);
            created.addInternalListener(listener);
            connections.put(remotePeer, created);
            refreshDirectNeighbors();
            onSocketAvailable(remotePeer, created);
            return created;
        }
    }

    private synchronized void refreshDirectNeighbors() {
        if (closed) return;
        Map<NodeId, NostrRTCSocket> socketsByNode = new HashMap<NodeId, NostrRTCSocket>();
        List<NodeId> membership = new ArrayList<NodeId>(connections.size() + 1);
        membership.add(localNodeId);
        for (Map.Entry<NostrRTCPeer, NostrRTCSocket> entry : connections.entrySet()) {
            NostrRTCPeer peer = entry.getKey();
            NostrRTCSocket socket = entry.getValue();
            if (peer == null || peer.getPubkey() == null || socket == null || socket.isClosed()) {
                continue;
            }
            NodeId node = NodeId.derive(routingScope, peer.getPubkey(), peer.getSessionId());
            membership.add(node);
            socketsByNode.put(node, socket);
        }
        Instant now = Instant.now();
        Collection<NostrRTCConnectSignal> announces = signaling.getAnnounces();
        topologyControl.updatePresences(announces, now);
        List<NostrRTCPeer> routedPresences = new ArrayList<NostrRTCPeer>();
        routedPresences.add(localPeer);
        for (NostrRTCConnectSignal announce : announces) {
            if (announce.supportsRouting() && !announce.isExpired(now)) {
                routedPresences.add(announce.getPeer());
            }
        }
        TopologyGraph graph = graphBuilder.build(routingScope, routedPresences, topologyControl.getSnapshots(now), now);
        String previousGraphId = routingGraph.getSnapshotId();
        routingGraph = graph;
        recentRoutingGraphs.put(graph.getSnapshotId(), graph);
        while (recentRoutingGraphs.size() > 2) {
            recentRoutingGraphs.remove(recentRoutingGraphs.keySet().iterator().next());
        }
        OverlayPlan plan = neighborManager.update(routingScope, membership, settings.getMaxDirectPeers(), graph, now);
        Set<NodeId> desiredNeighbors = plan.getNeighbors(localNodeId);
        for (Map.Entry<NodeId, NostrRTCSocket> entry : socketsByNode.entrySet()) {
            boolean enabled = desiredNeighbors.contains(entry.getKey());
            entry.getValue().setPhysicalLinkEnabled(enabled);
            if (enabled) {
                entry.getValue().createChannel(InternalRoutingChannels.CONTROL, true, true, null, null);
                ensureInternalProfileChannels(entry.getValue(), RouteTransportProfile.RELIABLE_ORDERED);
                ensureInternalProfileChannels(entry.getValue(), RouteTransportProfile.UNRELIABLE_UNORDERED);
            }
        }
        List<TopologyNeighbor> publishedNeighbors = new ArrayList<TopologyNeighbor>();
        for (Map.Entry<NodeId, NostrRTCSocket> entry : socketsByNode.entrySet()) {
            NostrRTCSocket socket = entry.getValue();
            if (
                !desiredNeighbors.contains(entry.getKey()) ||
                !socket.isPhysicalLinkEnabled() ||
                !socket.hasUsableTransport() ||
                !supportsRouting(socket.getRemotePeer(), announces)
            ) {
                continue;
            }
            NostrRTCPeer peer = socket.getRemotePeer();
            publishedNeighbors.add(
                new TopologyNeighbor(
                    entry.getKey(),
                    peer.getPubkey(),
                    peer.getSessionId(),
                    EdgeId.derive(routingScope, localNodeId, entry.getKey()),
                    topologyTransport(socket.getActiveTransportPath())
                )
            );
        }
        topologyControl.requestPublish(publishedNeighbors);
        if (!previousGraphId.equals(graph.getSnapshotId())) {
            drainAllPendingSends();
        }
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
    public synchronized void close() {
        if (closed) return;
        closed = true;
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
        recentRoutingGraphs.clear();
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
        this.executor.runLater(
                () -> {
                    if (closed) return null;
                    try {
                        // try to connect to every announced peer
                        Collection<NostrRTCConnectSignal> announces = this.signaling.getAnnounces();
                        for (NostrRTCConnectSignal announce : announces) {
                            NostrRTCPeer remotePeer = announce.getPeer();
                            NostrPublicKey remotePubkey = remotePeer.getPubkey();

                            NostrRTCSocket socket = ensureLogicalSocket(remotePeer);
                            if (socket == null || !socket.isPhysicalLinkEnabled()) continue;

                            if (shouldDeferRtcAttempt(socket)) continue;
                            synchronized (this) {
                                socket = connections.get(remotePeer); // make sure we have a fresh reference to the socket
                                // it could have changed while we were waiting for the lock
                                if (shouldDeferRtcAttempt(socket)) continue;
                                if (socket != null && socket.isClosed()) {
                                    logger.fine("Dropping closed socket for peer: " + remotePubkey);
                                    connections.remove(remotePeer, socket);
                                    socket = null;
                                }

                                if (!shouldOfferConnection(remotePubkey)) continue;

                                logger.fine("Initiating connection to: " + remotePubkey);
                                if (socket == null) {
                                    socket = ensureLogicalSocket(remotePeer);
                                } else {
                                    socket.prepareRtcTransportAttempt();
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

                    if (!closed) this.loop();
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
        this.topologyControl.start();
        this.loop();
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

    private void onAddAnnounce(NostrRTCConnectSignal announce) {
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
            connections.remove(remotePeer, socket);
            refreshDirectNeighbors();
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

    private void onReceiveOffer(NostrRTCOfferSignal offer) {
        synchronized (this) {
            NostrRTCPeer remotePeer = offer.getPeer();
            // offer received from remote peer
            NostrRTCSocket existing = ensureLogicalSocket(remotePeer);
            if (existing == null || !existing.isPhysicalLinkEnabled()) {
                logger.fine("Ignoring offer from peer outside the selected direct-neighbor set: " + remotePeer);
                return;
            }
            NostrRTCSocket socket = null;
            if (existing != null && existing.isPendingConnection() && !shouldOfferConnection(remotePeer.getPubkey())) {
                // if there is already a connection initiated to this peer, forfeit it if the
                // remote has precedence over local.
                logger.fine(
                    "Forfeiting connection to peer: " +
                    remotePeer +
                    " because remote peer has precedence over local peer and is initiating the connection"
                );
                existing.prepareRtcTransportAttempt();
                socket = existing;
            } else if (existing != null && existing.isRTCConnected()) {
                logger.fine("Socket already exists for peer: " + remotePeer + ", ignoring offer");
                return;
            } else if (existing != null && !existing.isClosed()) {
                socket = existing;
                socket.prepareRtcTransportAttempt();
            }

            logger.fine("Connecting to peer: " + remotePeer);
            if (socket == null) {
                socket = ensureLogicalSocket(remotePeer);
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

    private void onReceiveAnswer(NostrRTCAnswerSignal answer) {
        synchronized (this) {
            // answer received from remote peer
            NostrRTCPeer remotePeer = answer.getPeer();

            NostrRTCSocket socket = connections.get(remotePeer);
            if (
                socket != null &&
                socket.isPhysicalLinkEnabled() &&
                socket.isPendingConnection() &&
                shouldOfferConnection(remotePeer.getPubkey())
            ) {
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
                logger.fine("No pending connection for peer: " + remotePeer);
            }
        }
    }

    private void onReceiveCandidates(NostrRTCRouteSignal candidate) {
        logger.fine("Received ICE candidate: " + candidate);
        NostrRTCPeer remotePeer = candidate.getPeer();

        // receive remote candidate, add it to the socket
        NostrRTCSocket socket = connections.get(remotePeer);
        if (socket != null && socket.isPhysicalLinkEnabled()) {
            socket.mergeRemoteRTCIceCandidate(candidate);
        } else {
            logger.fine("No socket found for peer: " + remotePeer);
        }
    }

    private void onRTCSocketLocalIceCandidate(
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
