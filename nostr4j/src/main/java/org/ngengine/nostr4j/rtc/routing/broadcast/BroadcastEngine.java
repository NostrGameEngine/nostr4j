/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.ngengine.nostr4j.rtc.routing.CircuitId;
import org.ngengine.nostr4j.rtc.routing.NeighborTrafficLimiter;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutingLimits;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class BroadcastEngine implements Closeable {

    private static final Duration DEFAULT_LIFETIME = Duration.ofSeconds(30);
    private static final long DEFAULT_ACK_TIMEOUT_MS = 12_000L;

    private final NodeId localNode;
    private final BroadcastContext context;
    private final BroadcastTreeBuilder trees = new BroadcastTreeBuilder();
    private final BroadcastDedupCache dedup = new BroadcastDedupCache();
    private final NeighborTrafficLimiter trafficLimiter;
    private final AsyncExecutor executor;
    private final long ackTimeoutMs;
    private final Map<CircuitId, PendingBroadcast> pending = new HashMap<CircuitId, PendingBroadcast>();
    private volatile boolean closed;

    public BroadcastEngine(NodeId localNode, BroadcastContext context) {
        this(localNode, context, DEFAULT_ACK_TIMEOUT_MS, new NeighborTrafficLimiter());
    }

    public BroadcastEngine(NodeId localNode, BroadcastContext context, NeighborTrafficLimiter trafficLimiter) {
        this(localNode, context, DEFAULT_ACK_TIMEOUT_MS, trafficLimiter);
    }

    BroadcastEngine(NodeId localNode, BroadcastContext context, long ackTimeoutMs) {
        this(localNode, context, ackTimeoutMs, new NeighborTrafficLimiter());
    }

    private BroadcastEngine(
        NodeId localNode,
        BroadcastContext context,
        long ackTimeoutMs,
        NeighborTrafficLimiter trafficLimiter
    ) {
        if (ackTimeoutMs <= 0) throw new IllegalArgumentException("Broadcast ACK timeout must be positive");
        this.localNode = localNode;
        this.context = context;
        this.trafficLimiter = Objects.requireNonNull(trafficLimiter, "trafficLimiter");
        this.ackTimeoutMs = ackTimeoutMs;
        this.executor = NGEUtils.getPlatform().newAsyncExecutor(BroadcastEngine.class);
    }

    public AsyncTask<Boolean> broadcast(String logicalChannel, RouteTransportProfile profile, ByteBuffer payload, Instant now) {
        if (closed) return AsyncTask.failed(new IllegalStateException("Broadcast engine is closed"));
        TopologyGraph graph = context.currentGraph();
        BroadcastTree tree = trees.build(graph, localNode);
        CircuitId id = CircuitId.random();
        int maximumDepth = 1;
        for (NodeId node : tree.getNodes()) maximumDepth = Math.max(maximumDepth, tree.getDepth(node));
        BroadcastFrame frame = new BroadcastFrame(
            localNode,
            id,
            graph.getSnapshotId(),
            logicalChannel,
            profile,
            maximumDepth,
            now.plus(DEFAULT_LIFETIME),
            payload
        );
        dedup.markIfNew(localNode, id, logicalChannel, frame.getExpiresAt(), now);
        PendingBroadcast tracker = null;
        if (profile.requiresDestinationAck()) {
            Set<NodeId> targets = new HashSet<NodeId>(tree.getNodes());
            targets.remove(localNode);
            if (targets.isEmpty()) {
                return sendChildren(tree, frame, localNode);
            }
            synchronized (this) {
                if (pending.size() >= RoutingLimits.MAX_BROADCAST_TRACKERS) {
                    return AsyncTask.failed(new IllegalStateException("Broadcast tracker limit exceeded"));
                }
                tracker = new PendingBroadcast(id, logicalChannel, targets, frame);
                pending.put(id, tracker);
                scheduleTrackerTimeout(tracker);
            }
        }
        AsyncTask<Boolean> sent = sendChildren(tree, frame, localNode);
        return tracker == null ? sent : tracker.completion;
    }

    public AsyncTask<Boolean> onTreeFrame(NodeId previousDirectPeer, ByteBuffer encoded, Instant now) {
        if (closed) return AsyncTask.completed(Boolean.FALSE);
        long receivedAtMs = System.currentTimeMillis();
        if (previousDirectPeer == null || encoded == null) return AsyncTask.completed(Boolean.FALSE);
        NeighborTrafficLimiter.Admission admission = trafficLimiter.tryAcquire(
            previousDirectPeer,
            encoded.remaining(),
            false,
            receivedAtMs
        );
        if (admission == null) return AsyncTask.completed(Boolean.FALSE);
        final BroadcastFrame frame;
        final BroadcastTree tree;
        try (NeighborTrafficLimiter.Admission ignored = admission) {
            frame = BroadcastFrame.decode(encoded, now);
            TopologyGraph graph = context.graphBySnapshotId(frame.getGraphSnapshotId());
            if (graph == null) return AsyncTask.completed(Boolean.FALSE);
            tree = trees.build(graph, frame.getOrigin());
            NodeId expectedParent = tree.getParent(localNode);
            if (expectedParent == null || !expectedParent.equals(previousDirectPeer)) {
                return AsyncTask.completed(Boolean.FALSE);
            }
            int depth = tree.getDepth(localNode);
            if (depth < 1 || depth > frame.getHopLimit()) return AsyncTask.completed(Boolean.FALSE);
        } catch (Throwable error) {
            trafficLimiter.recordMalformed(previousDirectPeer, receivedAtMs);
            return AsyncTask.completed(Boolean.FALSE);
        }
        boolean first = dedup.markIfNew(
            frame.getOrigin(),
            frame.getBroadcastId(),
            frame.getLogicalChannel(),
            frame.getExpiresAt(),
            now
        );
        List<AsyncTask<Boolean>> tasks = new ArrayList<AsyncTask<Boolean>>();
        if (first) {
            context.deliverBroadcast(frame.getOrigin(), frame.getLogicalChannel(), frame.getPayload());
            for (NodeId child : tree.getChildren(localNode)) {
                tasks.add(context.sendTreeEdge(child, frame.getProfile(), frame.forwardingView()));
            }
        }
        if (frame.getProfile().requiresDestinationAck()) {
            tasks.add(
                context.sendAck(
                    new BroadcastAck(frame.getOrigin(), localNode, frame.getBroadcastId(), frame.getLogicalChannel())
                )
            );
        }
        return settle(tasks);
    }

    public AsyncTask<Boolean> onRepairFrame(ByteBuffer encoded, Instant now) {
        if (closed) return AsyncTask.completed(Boolean.FALSE);
        final BroadcastFrame frame;
        try {
            frame = BroadcastFrame.decode(encoded, now);
            if (context.graphBySnapshotId(frame.getGraphSnapshotId()) == null) {
                return AsyncTask.completed(Boolean.FALSE);
            }
        } catch (Throwable error) {
            return AsyncTask.completed(Boolean.FALSE);
        }
        boolean first = dedup.markIfNew(
            frame.getOrigin(),
            frame.getBroadcastId(),
            frame.getLogicalChannel(),
            frame.getExpiresAt(),
            now
        );
        if (first) {
            context.deliverBroadcast(frame.getOrigin(), frame.getLogicalChannel(), frame.getPayload());
        }
        if (!frame.getProfile().requiresDestinationAck()) return AsyncTask.completed(Boolean.TRUE);
        return context.sendAck(
            new BroadcastAck(frame.getOrigin(), localNode, frame.getBroadcastId(), frame.getLogicalChannel())
        );
    }

    public boolean onAck(BroadcastAck ack) {
        if (!localNode.equals(ack.getOrigin())) return false;
        PendingBroadcast tracker;
        synchronized (this) {
            tracker = pending.get(ack.getBroadcastId());
            if (
                tracker == null ||
                !tracker.logicalChannel.equals(ack.getLogicalChannel()) ||
                !tracker.targets.contains(ack.getResponder())
            ) {
                return false;
            }
            tracker.received.add(ack.getResponder());
            if (!tracker.received.containsAll(tracker.targets)) return true;
            pending.remove(ack.getBroadcastId());
        }
        tracker.resolve(Boolean.TRUE);
        return true;
    }

    private AsyncTask<Boolean> sendChildren(BroadcastTree tree, BroadcastFrame frame, NodeId parent) {
        List<AsyncTask<Boolean>> tasks = new ArrayList<AsyncTask<Boolean>>();
        for (NodeId child : tree.getChildren(parent)) {
            tasks.add(context.sendTreeEdge(child, frame.getProfile(), frame.forwardingView()));
        }
        return settle(tasks);
    }

    private static AsyncTask<Boolean> settle(List<AsyncTask<Boolean>> tasks) {
        if (tasks.isEmpty()) return AsyncTask.completed(Boolean.TRUE);
        return NGEPlatform.get().awaitAllSettled(tasks).then(ignored -> Boolean.TRUE);
    }

    private void scheduleTrackerTimeout(PendingBroadcast tracker) {
        tracker.timeout =
            executor.runLater(
                () -> {
                    Set<NodeId> missing;
                    synchronized (BroadcastEngine.this) {
                        if (!pending.containsKey(tracker.id)) return null;
                        missing = new HashSet<NodeId>(tracker.targets);
                        missing.removeAll(tracker.received);
                        if (tracker.repairStarted) {
                            pending.remove(tracker.id);
                            tracker.resolve(Boolean.FALSE);
                            return null;
                        }
                        tracker.repairStarted = true;
                    }
                    for (NodeId target : missing) {
                        context.repairUnicast(target, tracker.frame.forwardingView());
                    }
                    scheduleTrackerTimeout(tracker);
                    return null;
                },
                ackTimeoutMs,
                TimeUnit.MILLISECONDS
            );
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        List<PendingBroadcast> trackers;
        synchronized (this) {
            trackers = new ArrayList<PendingBroadcast>(pending.values());
            pending.clear();
        }
        for (PendingBroadcast tracker : trackers) tracker.resolve(Boolean.FALSE);
        dedup.clear();
        trafficLimiter.close();
        executor.close();
    }

    private static final class PendingBroadcast {

        private final CircuitId id;
        private final String logicalChannel;
        private final Set<NodeId> targets;
        private final Set<NodeId> received = new HashSet<NodeId>();
        private final BroadcastFrame frame;
        private final AsyncTask<Boolean> completion;
        private Consumer<Boolean> resolver;
        private Boolean early;
        private boolean repairStarted;
        private AsyncTask<Void> timeout;

        private PendingBroadcast(CircuitId id, String logicalChannel, Set<NodeId> targets, BroadcastFrame frame) {
            this.id = id;
            this.logicalChannel = logicalChannel;
            this.targets = Collections.unmodifiableSet(new HashSet<NodeId>(targets));
            this.frame = frame;
            this.completion =
                NGEPlatform
                    .get()
                    .wrapPromise((resolve, reject) -> {
                        synchronized (this) {
                            resolver = resolve;
                            if (early != null) resolve.accept(early);
                        }
                    });
        }

        private synchronized void resolve(Boolean value) {
            if (timeout != null) timeout.cancel();
            if (resolver != null) resolver.accept(value); else early = value;
        }
    }
}
