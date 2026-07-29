/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.time.Instant;
import org.junit.Test;
import org.ngengine.platform.NGEUtils;

public class TestCircuitTable {

    @Test
    public void testPreviousPeerScopesLookupAndExpiryCleansState() {
        CircuitTable table = new CircuitTable(4, 2);
        Instant now = Instant.now();
        NodeId previous = node(1);
        NodeId wrongPrevious = node(2);
        NodeId next = node(3);
        CircuitId id = CircuitId.random();
        table.install(previous, id, next, RouteTransportProfile.RELIABLE_ORDERED, now.plusSeconds(10), now);
        assertEquals(next, table.find(previous, id, now).getNextDirectPeer());
        assertNull(table.find(wrongPrevious, id, now));
        assertNull(table.find(previous, id, now.plusSeconds(11)));
        assertEquals(0, table.size());
    }

    @Test
    public void testGlobalAndPerNeighborLimitsAreEnforcedAndCloseClears() {
        CircuitTable table = new CircuitTable(3, 2);
        Instant now = Instant.now();
        NodeId first = node(10);
        NodeId second = node(11);
        NodeId next = node(12);
        table.install(first, CircuitId.random(), next, RouteTransportProfile.RELIABLE_ORDERED, now.plusSeconds(10), now);
        table.install(first, CircuitId.random(), next, RouteTransportProfile.RELIABLE_ORDERED, now.plusSeconds(10), now);
        assertThrows(
            IllegalStateException.class,
            () ->
                table.install(first, CircuitId.random(), next, RouteTransportProfile.RELIABLE_ORDERED, now.plusSeconds(10), now)
        );
        table.install(second, CircuitId.random(), next, RouteTransportProfile.RELIABLE_ORDERED, now.plusSeconds(10), now);
        assertThrows(
            IllegalStateException.class,
            () ->
                table.install(
                    node(13),
                    CircuitId.random(),
                    next,
                    RouteTransportProfile.RELIABLE_ORDERED,
                    now.plusSeconds(10),
                    now
                )
        );
        table.close();
        assertEquals(0, table.size());
        assertThrows(IllegalStateException.class, () -> table.find(first, CircuitId.random(), now));
    }

    private static NodeId node(int value) {
        byte[] bytes = new byte[NodeId.SIZE];
        bytes[NodeId.SIZE - 1] = (byte) value;
        return NodeId.fromHex(NGEUtils.bytesToHex(bytes));
    }
}
