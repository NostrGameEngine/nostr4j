/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;

/**
 * Stateful bounded-degree overlay policy. Backbone and repair edges displace
 * optional chords; healthy optional chords survive brief membership churn.
 */
public final class DirectNeighborManager {

    private static final Duration DEFAULT_REPAIR_GRACE = Duration.ofSeconds(5);
    private static final Duration DEFAULT_CHORD_MINIMUM_LIFETIME = Duration.ofSeconds(30);

    private final BoundedOverlaySelector selector = new BoundedOverlaySelector();
    private final PartitionRepairPlanner repairPlanner = new PartitionRepairPlanner();
    private final Duration repairGrace;
    private final Duration chordMinimumLifetime;
    private final Map<DesiredDirectEdge, Instant> optionalSince = new HashMap<DesiredDirectEdge, Instant>();

    private Set<NodeId> previousMembership = Collections.emptySet();
    private Instant membershipChangedAt = Instant.EPOCH;
    private OverlayPlan current = new OverlayPlan(Collections.emptyList(), Collections.emptySet());

    public DirectNeighborManager() {
        this(DEFAULT_REPAIR_GRACE, DEFAULT_CHORD_MINIMUM_LIFETIME);
    }

    DirectNeighborManager(Duration repairGrace, Duration chordMinimumLifetime) {
        this.repairGrace = repairGrace;
        this.chordMinimumLifetime = chordMinimumLifetime;
    }

    public synchronized OverlayPlan update(
        RoutingScope scope,
        List<NodeId> membership,
        int maxDirectPeers,
        TopologyGraph graph,
        Instant now
    ) {
        Set<NodeId> membershipSet = new HashSet<NodeId>(membership);
        if (!membershipSet.equals(previousMembership)) {
            previousMembership = Collections.unmodifiableSet(new HashSet<NodeId>(membershipSet));
            membershipChangedAt = now;
        }
        OverlayPlan selected = selector.select(scope, membership, maxDirectPeers);
        Map<DesiredDirectEdge, DesiredDirectEdge> desired = new HashMap<DesiredDirectEdge, DesiredDirectEdge>();

        List<DesiredDirectEdge> selectedEdges = sorted(selected.getEdges());
        for (DesiredDirectEdge edge : selectedEdges) {
            if (edge.getPriority() == OverlayEdgePriority.BACKBONE) {
                desired.put(edge, edge);
            }
        }

        for (DesiredDirectEdge old : sorted(current.getEdges())) {
            if (
                old.getPriority() == OverlayEdgePriority.CHORD &&
                membershipSet.contains(old.getFirst()) &&
                membershipSet.contains(old.getSecond()) &&
                isYoungOptional(old, now)
            ) {
                addIfCapacity(desired, old, maxDirectPeers);
            }
        }
        for (DesiredDirectEdge edge : selectedEdges) {
            if (edge.getPriority() == OverlayEdgePriority.CHORD && addIfCapacity(desired, edge, maxDirectPeers)) {
                optionalSince.putIfAbsent(edge, now);
            }
        }

        if (graph != null && graph.connectedComponents().size() > 1 && !now.isBefore(membershipChangedAt.plus(repairGrace))) {
            for (DesiredDirectEdge repair : repairPlanner.plan(scope, graph)) {
                installMandatory(desired, repair, maxDirectPeers);
            }
        }

        optionalSince
            .keySet()
            .removeIf(edge -> !desired.containsKey(edge) || desired.get(edge).getPriority() != OverlayEdgePriority.CHORD);
        List<NodeId> orderedMembership = new ArrayList<NodeId>(membershipSet);
        Collections.sort(orderedMembership);
        current = new OverlayPlan(orderedMembership, new HashSet<DesiredDirectEdge>(desired.values()));
        return current;
    }

    public synchronized OverlayPlan getCurrentPlan() {
        return current;
    }

    private boolean isYoungOptional(DesiredDirectEdge edge, Instant now) {
        Instant since = optionalSince.get(edge);
        return since != null && now.isBefore(since.plus(chordMinimumLifetime));
    }

    private static boolean addIfCapacity(Map<DesiredDirectEdge, DesiredDirectEdge> desired, DesiredDirectEdge edge, int max) {
        if (desired.containsKey(edge)) return true;
        if (degree(desired, edge.getFirst()) >= max || degree(desired, edge.getSecond()) >= max) {
            return false;
        }
        desired.put(edge, edge);
        return true;
    }

    private static void installMandatory(
        Map<DesiredDirectEdge, DesiredDirectEdge> desired,
        DesiredDirectEdge mandatory,
        int max
    ) {
        DesiredDirectEdge existing = desired.get(mandatory);
        if (existing != null) {
            if (existing.getPriority().ordinal() > mandatory.getPriority().ordinal()) {
                desired.put(mandatory, mandatory);
            }
            return;
        }
        if (!makeCapacity(desired, mandatory.getFirst(), max)) return;
        if (!makeCapacity(desired, mandatory.getSecond(), max)) return;
        if (degree(desired, mandatory.getFirst()) < max && degree(desired, mandatory.getSecond()) < max) {
            desired.put(mandatory, mandatory);
        }
    }

    private static boolean makeCapacity(Map<DesiredDirectEdge, DesiredDirectEdge> desired, NodeId endpoint, int max) {
        while (degree(desired, endpoint) >= max) {
            DesiredDirectEdge removable = desired
                .values()
                .stream()
                .filter(edge -> edge.contains(endpoint) && edge.getPriority() == OverlayEdgePriority.CHORD)
                .max(
                    Comparator
                        .comparing((DesiredDirectEdge edge) -> edge.other(endpoint))
                        .thenComparing(edge -> edge.getFirst())
                )
                .orElse(null);
            if (removable == null) return false;
            desired.remove(removable);
        }
        return true;
    }

    private static int degree(Map<DesiredDirectEdge, DesiredDirectEdge> edges, NodeId node) {
        int degree = 0;
        for (DesiredDirectEdge edge : edges.values()) {
            if (edge.contains(node)) degree++;
        }
        return degree;
    }

    private static List<DesiredDirectEdge> sorted(Set<DesiredDirectEdge> edges) {
        List<DesiredDirectEdge> sorted = new ArrayList<DesiredDirectEdge>(edges);
        sorted.sort(
            Comparator
                .comparing(DesiredDirectEdge::getPriority)
                .thenComparing(DesiredDirectEdge::getFirst)
                .thenComparing(DesiredDirectEdge::getSecond)
        );
        return sorted;
    }
}
