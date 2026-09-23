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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;

/**
 * JSON snapshot of a room's dc4 routing topology for the website demos.
 *
 * <p>{@code NostrRTCRoom.getRoutingTopology()} is package-private by design
 * (the topology is an internal routing detail). This tiny same-package
 * bridge exposes a read-only JSON snapshot so the site's topology graph
 * panels can render the live mesh. It lives in the {@code site-demos}
 * module, next to the demos that use it — not in nostr4j itself.</p>
 */
public final class TopologyJsonBridge {

    private TopologyJsonBridge() {}

    /**
     * Snapshot the room's current mutually-attested routing graph.
     *
     * @param room         the room to snapshot
     * @param localNodeHex hex of the local {@link NodeId} (may be null)
     * @return JSON: {@code {"t":"topo","self":"...","nodes":[{"id","short"}],
     *         "edges":[{"a","b","t":"rtc"|"turn"|"unknown"}]}}; nodes and edges
     *         are sorted for stable output
     */
    public static String snapshot(NostrRTCRoom room, String localNodeHex) {
        TopologyGraph graph = room.getRoutingTopology();
        List<NodeId> nodes = new ArrayList<NodeId>(graph.getNodes());
        Collections.sort(nodes);
        List<TopologyEdge> edges = new ArrayList<TopologyEdge>(graph.getEdges());
        Collections.sort(
            edges,
            new Comparator<TopologyEdge>() {
                @Override
                public int compare(TopologyEdge a, TopologyEdge b) {
                    return a.getEdgeId().asHex().compareTo(b.getEdgeId().asHex());
                }
            }
        );

        StringBuilder b = new StringBuilder(1024);
        b.append("{\"t\":\"topo\",\"self\":");
        if (localNodeHex == null) {
            b.append("null");
        } else {
            b.append('"').append(localNodeHex).append('"');
        }
        b.append(",\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) b.append(',');
            String hex = nodes.get(i).asHex();
            b
                .append("{\"id\":\"")
                .append(hex)
                .append("\",\"short\":\"")
                .append(hex.substring(0, Math.min(8, hex.length())))
                .append("\"}");
        }
        b.append("],\"edges\":[");
        for (int i = 0; i < edges.size(); i++) {
            if (i > 0) b.append(',');
            TopologyEdge e = edges.get(i);
            b
                .append("{\"a\":\"")
                .append(e.getFirst().asHex())
                .append("\",\"b\":\"")
                .append(e.getSecond().asHex())
                .append("\",\"t\":\"")
                .append(e.getEffectiveTransport().name().toLowerCase(java.util.Locale.ROOT))
                .append("\"}");
        }
        b.append("]}");
        return b.toString();
    }
}
