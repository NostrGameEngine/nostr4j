/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.platform.NGEPlatform;

public final class BoundedOverlaySelector {

    private static final byte[] CHORD_DOMAIN = "nip-dc-routing-chord-v1".getBytes(StandardCharsets.UTF_8);

    public OverlayPlan select(RoutingScope scope, List<NodeId> membership, int maxDirectPeers) {
        if (maxDirectPeers < 2) {
            throw new IllegalArgumentException("maxDirectPeers must be at least 2");
        }
        List<NodeId> nodes = new ArrayList<NodeId>(new HashSet<NodeId>(membership));
        Collections.sort(nodes);
        Set<DesiredDirectEdge> edges = new HashSet<DesiredDirectEdge>();
        int size = nodes.size();
        if (size <= 1) {
            return new OverlayPlan(nodes, edges);
        }
        if (size - 1 <= maxDirectPeers) {
            for (int left = 0; left < size; left++) {
                for (int right = left + 1; right < size; right++) {
                    edges.add(new DesiredDirectEdge(nodes.get(left), nodes.get(right), OverlayEdgePriority.BACKBONE));
                }
            }
            return new OverlayPlan(nodes, edges);
        }

        int ringRadius = maxDirectPeers >= 4 ? 2 : 1;
        for (int index = 0; index < size; index++) {
            for (int distance = 1; distance <= ringRadius; distance++) {
                addEdge(edges, nodes.get(index), nodes.get((index + distance) % size), OverlayEdgePriority.BACKBONE);
            }
        }

        byte[] membershipDigest = membershipDigest(nodes);
        int maxRounds = Math.max(16, maxDirectPeers * 16);
        for (int round = 0; round < maxRounds && hasCapacity(nodes, edges, maxDirectPeers); round++) {
            List<ScoredNode> scored = new ArrayList<ScoredNode>(size);
            for (NodeId node : nodes) {
                scored.add(new ScoredNode(node, chordScore(scope, membershipDigest, round, node)));
            }
            scored.sort(
                Comparator
                    .comparing((ScoredNode value) -> value.score, BoundedOverlaySelector::compareUnsigned)
                    .thenComparing(value -> value.node)
            );
            for (int index = 0; index + 1 < scored.size(); index += 2) {
                NodeId first = scored.get(index).node;
                NodeId second = scored.get(index + 1).node;
                if (degree(edges, first) >= maxDirectPeers || degree(edges, second) >= maxDirectPeers) {
                    continue;
                }
                addEdge(edges, first, second, OverlayEdgePriority.CHORD);
            }
        }
        return new OverlayPlan(nodes, edges);
    }

    private static void addEdge(Set<DesiredDirectEdge> edges, NodeId first, NodeId second, OverlayEdgePriority priority) {
        if (!first.equals(second)) {
            edges.add(new DesiredDirectEdge(first, second, priority));
        }
    }

    private static boolean hasCapacity(List<NodeId> nodes, Set<DesiredDirectEdge> edges, int max) {
        for (NodeId node : nodes) {
            if (degree(edges, node) < max) {
                return true;
            }
        }
        return false;
    }

    private static int degree(Set<DesiredDirectEdge> edges, NodeId node) {
        int degree = 0;
        for (DesiredDirectEdge edge : edges) {
            if (edge.contains(node)) {
                degree++;
            }
        }
        return degree;
    }

    private static byte[] membershipDigest(List<NodeId> nodes) {
        ByteBuffer encoded = ByteBuffer.allocate(nodes.size() * NodeId.SIZE);
        for (NodeId node : nodes) {
            encoded.put(node.toByteArray());
        }
        return NGEPlatform.get().sha256(encoded.array());
    }

    private static byte[] chordScore(RoutingScope scope, byte[] membershipDigest, int round, NodeId node) {
        ByteBuffer scopeBytes = scope.canonicalBytes();
        ByteBuffer encoded = ByteBuffer.allocate(
            CHORD_DOMAIN.length + scopeBytes.remaining() + membershipDigest.length + Integer.BYTES + NodeId.SIZE
        );
        encoded.put(CHORD_DOMAIN);
        encoded.put(scopeBytes);
        encoded.put(membershipDigest);
        encoded.putInt(round);
        encoded.put(node.toByteArray());
        return NGEPlatform.get().sha256(encoded.array());
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private static final class ScoredNode {

        private final NodeId node;
        private final byte[] score;

        private ScoredNode(NodeId node, byte[] score) {
            this.node = node;
            this.score = Arrays.copyOf(score, score.length);
        }
    }
}
