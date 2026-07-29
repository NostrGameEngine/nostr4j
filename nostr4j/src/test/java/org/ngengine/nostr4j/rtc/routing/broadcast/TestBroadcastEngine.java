/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.broadcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

public class TestBroadcastEngine {

    @Test
    public void testStableTreeUsesNMinusOneForwardsAndRpfDropsNonParentDuplicate() {
        TestNetwork network = new TestNetwork(8, 50L);
        try {
            NodeId root = network.nodes.get(0);
            Boolean result = NGEUtils.awaitNoThrow(
                network.engines
                    .get(root)
                    .broadcast(
                        "game",
                        RouteTransportProfile.UNRELIABLE_UNORDERED,
                        ByteBuffer.wrap(new byte[] { 1, 2, 3 }),
                        Instant.now()
                    )
            );
            assertTrue(result.booleanValue());
            assertEquals(network.nodes.size() - 1, network.payloadForwards.get());
            assertEquals(0, network.ackCount.get());
            for (NodeId node : network.nodes) {
                assertEquals(node.equals(root) ? 0 : 1, network.deliveries.get(node).get());
            }

            BroadcastTree tree = new BroadcastTreeBuilder().build(network.current, root);
            NodeId target = network.nodes
                .stream()
                .filter(node -> !node.equals(root) && tree.getParent(node) != null)
                .findFirst()
                .orElseThrow();
            NodeId wrongParent = network.nodes
                .stream()
                .filter(node -> !node.equals(tree.getParent(target)))
                .findFirst()
                .orElseThrow();
            int forwardsBefore = network.payloadForwards.get();
            int deliveriesBefore = network.deliveries.get(target).get();
            Boolean accepted = NGEUtils.awaitNoThrow(
                network.engines.get(target).onTreeFrame(wrongParent, network.lastFrame, Instant.now())
            );
            assertFalse(accepted.booleanValue());
            assertEquals(forwardsBefore, network.payloadForwards.get());
            assertEquals(deliveriesBefore, network.deliveries.get(target).get());
        } finally {
            network.close();
        }
    }

    @Test
    public void testReliableBroadcastCollectsAcksAndRepairsOnlyMissingPeers() throws Exception {
        TestNetwork network = new TestNetwork(10, 40L);
        try {
            NodeId root = network.nodes.get(0);
            BroadcastTree tree = new BroadcastTreeBuilder().build(network.current, root);
            NodeId droppedChild = tree.getChildren(root).get(0);
            network.dropOnce.add(new Link(root, droppedChild));
            Boolean result = network.engines
                .get(root)
                .broadcast(
                    "reliable",
                    RouteTransportProfile.RELIABLE_ORDERED,
                    ByteBuffer.wrap(new byte[] { 9, 8, 7 }),
                    Instant.now()
                )
                .await();
            assertTrue(result.booleanValue());
            assertTrue(network.repairCount.get() > 0);
            assertTrue(network.repairCount.get() < network.nodes.size());
            assertEquals(network.nodes.size() - 1, network.ackCount.get());
            for (NodeId node : network.nodes) {
                assertEquals(node.equals(root) ? 0 : 1, network.deliveries.get(node).get());
            }
        } finally {
            network.close();
        }
    }

    @Test
    public void testPreviousGraphSnapshotRemainsUsable() {
        TestNetwork network = new TestNetwork(5, 50L);
        try {
            NodeId root = network.nodes.get(0);
            BroadcastFrame previous = new BroadcastFrame(
                root,
                org.ngengine.nostr4j.rtc.routing.CircuitId.random(),
                network.current.getSnapshotId(),
                "previous",
                RouteTransportProfile.UNRELIABLE_UNORDERED,
                5,
                Instant.now().plusSeconds(10),
                ByteBuffer.wrap(new byte[] { 5 })
            );
            TopologyGraph prior = network.current;
            network.previous.put(prior.getSnapshotId(), prior);
            network.current = network.alternateGraph();
            BroadcastTree tree = new BroadcastTreeBuilder().build(prior, root);
            NodeId child = tree.getChildren(root).get(0);
            Boolean accepted = NGEUtils.awaitNoThrow(
                network.engines.get(child).onTreeFrame(root, previous.encode(), Instant.now())
            );
            assertTrue(accepted.booleanValue());
            assertEquals(1, network.deliveries.get(child).get());
        } finally {
            network.close();
        }
    }

    @Test
    public void testReliableSingleNodeBroadcastCompletesWithoutAckTimeout() {
        NodeId root = node(1);
        TopologyGraph graph = new TopologyGraph(Set.of(root), Set.of());
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger repairs = new AtomicInteger();
        BroadcastEngine engine = new BroadcastEngine(
            root,
            new BroadcastContext() {
                @Override
                public TopologyGraph currentGraph() {
                    return graph;
                }

                @Override
                public TopologyGraph graphBySnapshotId(String snapshotId) {
                    return graph.getSnapshotId().equals(snapshotId) ? graph : null;
                }

                @Override
                public AsyncTask<Boolean> sendTreeEdge(NodeId child, RouteTransportProfile profile, ByteBuffer encodedFrame) {
                    sends.incrementAndGet();
                    return AsyncTask.completed(Boolean.TRUE);
                }

                @Override
                public boolean deliverBroadcast(NodeId origin, String logicalChannel, ByteBuffer payload) {
                    return true;
                }

                @Override
                public AsyncTask<Boolean> sendAck(BroadcastAck ack) {
                    return AsyncTask.completed(Boolean.TRUE);
                }

                @Override
                public AsyncTask<Boolean> repairUnicast(NodeId target, ByteBuffer encodedFrame) {
                    repairs.incrementAndGet();
                    return AsyncTask.completed(Boolean.TRUE);
                }
            },
            30L
        );
        try {
            assertTrue(
                NGEUtils.awaitNoThrow(
                    engine.broadcast(
                        "solo",
                        RouteTransportProfile.RELIABLE_ORDERED,
                        ByteBuffer.wrap(new byte[] { 1 }),
                        Instant.now()
                    )
                )
            );
            assertEquals(0, sends.get());
            assertEquals(0, repairs.get());
        } finally {
            engine.close();
        }
    }

    private static final class TestNetwork {

        private final RoutingScope scope = new RoutingScope(
            new NostrKeyPair().getPublicKey(),
            "broadcast-proto",
            "broadcast-app"
        );
        private final List<NodeId> nodes = new ArrayList<NodeId>();
        private final Map<NodeId, BroadcastEngine> engines = new HashMap<NodeId, BroadcastEngine>();
        private final Map<NodeId, AtomicInteger> deliveries = new HashMap<NodeId, AtomicInteger>();
        private final AtomicInteger payloadForwards = new AtomicInteger();
        private final AtomicInteger ackCount = new AtomicInteger();
        private final AtomicInteger repairCount = new AtomicInteger();
        private final Set<Link> dropOnce = new HashSet<Link>();
        private final Map<String, TopologyGraph> previous = new HashMap<String, TopologyGraph>();
        private volatile TopologyGraph current;
        private volatile ByteBuffer lastFrame;

        private TestNetwork(int size, long ackTimeoutMs) {
            for (int index = 0; index < size; index++) nodes.add(node(index + 1));
            current = graph(nodes, false);
            for (NodeId node : nodes) {
                deliveries.put(node, new AtomicInteger());
                engines.put(node, new BroadcastEngine(node, new Context(node), ackTimeoutMs));
            }
        }

        private TopologyGraph alternateGraph() {
            return graph(nodes, true);
        }

        private TopologyGraph graph(List<NodeId> membership, boolean alternate) {
            Set<TopologyEdge> edges = new HashSet<TopologyEdge>();
            for (int index = 0; index < membership.size(); index++) {
                NodeId first = membership.get(index);
                NodeId second = membership.get((index + 1) % membership.size());
                edges.add(edge(first, second, alternate && index == 0 ? TopologyTransport.TURN : TopologyTransport.RTC));
                if (membership.size() > 4) {
                    NodeId chord = membership.get((index + (alternate ? 3 : 2)) % membership.size());
                    if (!first.equals(chord)) edges.add(edge(first, chord, TopologyTransport.RTC));
                }
            }
            return new TopologyGraph(new HashSet<NodeId>(membership), edges);
        }

        private TopologyEdge edge(NodeId first, NodeId second, TopologyTransport transport) {
            return new TopologyEdge(EdgeId.derive(scope, first, second), first, second, transport, transport);
        }

        private void close() {
            for (BroadcastEngine engine : engines.values()) engine.close();
        }

        private final class Context implements BroadcastContext {

            private final NodeId local;

            private Context(NodeId local) {
                this.local = local;
            }

            @Override
            public TopologyGraph currentGraph() {
                return current;
            }

            @Override
            public TopologyGraph graphBySnapshotId(String snapshotId) {
                if (current.getSnapshotId().equals(snapshotId)) return current;
                return previous.get(snapshotId);
            }

            @Override
            public AsyncTask<Boolean> sendTreeEdge(NodeId child, RouteTransportProfile profile, ByteBuffer encodedFrame) {
                payloadForwards.incrementAndGet();
                lastFrame = encodedFrame.asReadOnlyBuffer();
                Link link = new Link(local, child);
                if (dropOnce.remove(link)) return AsyncTask.completed(Boolean.TRUE);
                return engines.get(child).onTreeFrame(local, encodedFrame, Instant.now());
            }

            @Override
            public boolean deliverBroadcast(NodeId origin, String logicalChannel, ByteBuffer payload) {
                deliveries.get(local).incrementAndGet();
                return true;
            }

            @Override
            public AsyncTask<Boolean> sendAck(BroadcastAck ack) {
                ackCount.incrementAndGet();
                return AsyncTask.completed(Boolean.valueOf(engines.get(ack.getOrigin()).onAck(ack)));
            }

            @Override
            public AsyncTask<Boolean> repairUnicast(NodeId target, ByteBuffer encodedFrame) {
                repairCount.incrementAndGet();
                return engines.get(target).onRepairFrame(encodedFrame, Instant.now());
            }
        }
    }

    private static final class Link {

        private final NodeId from;
        private final NodeId to;

        private Link(NodeId from, NodeId to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Link && from.equals(((Link) other).from) && to.equals(((Link) other).to);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(from, to);
        }
    }

    private static NodeId node(int value) {
        byte[] bytes = new byte[NodeId.SIZE];
        bytes[NodeId.SIZE - 1] = (byte) value;
        return NodeId.fromHex(NGEUtils.bytesToHex(bytes));
    }
}
