/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.platform.NGEPlatform;

public final class PartitionRepairPlanner {

    private static final byte[] DOMAIN = "nip-dc-routing-repair-v1".getBytes(StandardCharsets.UTF_8);

    public List<DesiredDirectEdge> plan(RoutingScope scope, TopologyGraph graph) {
        List<Set<NodeId>> components = new ArrayList<Set<NodeId>>(graph.connectedComponents());
        components.sort(Comparator.comparing(PartitionRepairPlanner::minimum));
        if (components.size() <= 1) {
            return Collections.emptyList();
        }
        List<DesiredDirectEdge> repairs = new ArrayList<DesiredDirectEdge>();
        Set<NodeId> connected = components.get(0);
        for (int index = 1; index < components.size(); index++) {
            Candidate best = null;
            for (NodeId first : connected) {
                for (NodeId second : components.get(index)) {
                    Candidate candidate = new Candidate(first, second, score(scope, first, second));
                    if (best == null || candidate.compareTo(best) < 0) {
                        best = candidate;
                    }
                }
            }
            repairs.add(new DesiredDirectEdge(best.first, best.second, OverlayEdgePriority.REPAIR));
            java.util.HashSet<NodeId> merged = new java.util.HashSet<NodeId>(connected);
            merged.addAll(components.get(index));
            connected = merged;
        }
        return Collections.unmodifiableList(repairs);
    }

    private static NodeId minimum(Set<NodeId> component) {
        return Collections.min(component);
    }

    private static byte[] score(RoutingScope scope, NodeId first, NodeId second) {
        NodeId lower = first.compareTo(second) < 0 ? first : second;
        NodeId upper = first.compareTo(second) < 0 ? second : first;
        ByteBuffer scopeBytes = scope.canonicalBytes();
        ByteBuffer encoded = ByteBuffer.allocate(DOMAIN.length + scopeBytes.remaining() + NodeId.SIZE * 2);
        encoded.put(DOMAIN);
        encoded.put(scopeBytes);
        encoded.put(lower.toByteArray());
        encoded.put(upper.toByteArray());
        return NGEPlatform.get().sha256(encoded.array());
    }

    private static final class Candidate implements Comparable<Candidate> {

        private final NodeId first;
        private final NodeId second;
        private final byte[] score;

        private Candidate(NodeId first, NodeId second, byte[] score) {
            this.first = first;
            this.second = second;
            this.score = score;
        }

        @Override
        public int compareTo(Candidate other) {
            for (int index = 0; index < score.length; index++) {
                int comparison = Integer.compare(score[index] & 0xff, other.score[index] & 0xff);
                if (comparison != 0) return comparison;
            }
            int firstComparison = first.compareTo(other.first);
            return firstComparison != 0 ? firstComparison : second.compareTo(other.second);
        }
    }
}
