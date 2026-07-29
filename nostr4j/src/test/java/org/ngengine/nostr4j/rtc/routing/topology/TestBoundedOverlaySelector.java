/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing.topology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class TestBoundedOverlaySelector {

    private final RoutingScope scope = new RoutingScope(new NostrKeyPair().getPublicKey(), "overlay-proto", "overlay-app");
    private final BoundedOverlaySelector selector = new BoundedOverlaySelector();

    @Test
    public void testDeterministicConnectedDegreeBoundAcrossRequiredRoomSizes() {
        int[] sizes = new int[] { 2, 3, 5, 17, 32, 100, 500 };
        for (int size : sizes) {
            List<NodeId> nodes = nodes(size);
            OverlayPlan first = selector.select(scope, nodes, 16);
            OverlayPlan second = selector.select(scope, new ArrayList<NodeId>(nodes), 16);

            assertEquals("overlay must be deterministic for size " + size, first.getEdges(), second.getEdges());
            assertTrue("overlay must be connected for size " + size, first.isConnected());
            for (NodeId node : nodes) {
                assertTrue("degree cap exceeded for size " + size, first.degree(node) <= 16);
            }
            if (size <= 17) {
                assertEquals(size * (size - 1) / 2, first.getEdges().size());
            }
        }
    }

    @Test
    public void testMinimumDegreeUsesConnectedRing() {
        OverlayPlan plan = selector.select(scope, nodes(100), 2);
        assertTrue(plan.isConnected());
        for (NodeId node : plan.getNodes()) {
            assertEquals(2, plan.degree(node));
        }
        assertEquals(100, plan.getEdges().size());
    }

    private static List<NodeId> nodes(int count) {
        List<NodeId> nodes = new ArrayList<NodeId>(count);
        for (int index = 0; index < count; index++) {
            byte[] digest = NGEPlatform.get().sha256(("overlay-node-" + index).getBytes(StandardCharsets.UTF_8));
            nodes.add(NodeId.fromHex(NGEUtils.bytesToHex(digest)));
        }
        return nodes;
    }
}
