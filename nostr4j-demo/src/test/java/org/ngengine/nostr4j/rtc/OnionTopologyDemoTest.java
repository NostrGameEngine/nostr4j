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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutePath;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;

public class OnionTopologyDemoTest {

    @Test
    public void defaultsToNgengineRelayAndHeadfulMultiHopShape() {
        OnionTopologyDemo.Options options = OnionTopologyDemo.Options.parse(new String[0]);

        assertEquals("wss://relay.ngengine.org", options.relay);
        assertEquals(8, options.peerCount);
        assertEquals(2, options.maxDirectPeers);
        assertEquals(2, options.stunServers.size());
    }

    @Test
    public void parsesVerificationOptionsAndRejectsInvalidDegree() {
        OnionTopologyDemo.Options options = OnionTopologyDemo.Options.parse(
            new String[] { "--verify", "--peers=6", "--max-direct=2", "--timeout=90", "--no-stun" }
        );

        assertTrue(options.verify);
        assertEquals(6, options.peerCount);
        assertEquals(90, options.timeoutSeconds);
        assertTrue(options.stunServers.isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> OnionTopologyDemo.Options.parse(new String[] { "--peers=6", "--max-direct=1" })
        );
    }

    @Test
    public void selectsANonDirectMultiHopRouteFromActiveRtcEdges() {
        RoutingScope scope = new RoutingScope(
            new NostrKeyPair().getPublicKey(),
            OnionTopologyDemo.PROTOCOL_ID,
            OnionTopologyDemo.APPLICATION_ID
        );
        List<NodeId> nodes = List.of(node(1), node(2), node(3), node(4));
        Set<TopologyEdge> edges = new HashSet<TopologyEdge>();
        edges.add(edge(scope, nodes.get(0), nodes.get(1)));
        edges.add(edge(scope, nodes.get(1), nodes.get(2)));
        edges.add(edge(scope, nodes.get(2), nodes.get(3)));

        RoutePath route = OnionTopologyDemo.chooseMultiHopRoute(new TopologyGraph(new HashSet<NodeId>(nodes), edges));

        assertNotNull(route);
        assertEquals(3, route.getHopCount());
        assertEquals(nodes.get(0), route.getSource());
        assertEquals(nodes.get(3), route.getDestination());
    }

    private static TopologyEdge edge(RoutingScope scope, NodeId first, NodeId second) {
        return new TopologyEdge(
            EdgeId.derive(scope, first, second),
            first,
            second,
            TopologyTransport.RTC,
            TopologyTransport.RTC
        );
    }

    private static NodeId node(int value) {
        return NodeId.fromHex(String.format("%064x", value));
    }
}
