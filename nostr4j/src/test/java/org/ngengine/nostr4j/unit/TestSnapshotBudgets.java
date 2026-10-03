package org.ngengine.nostr4j.unit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;
import org.ngengine.nostr4j.NostrRelayInfo;
import org.ngengine.nostr4j.utils.ImmutableSnapshot;

public class TestSnapshotBudgets {

    @Test
    public void metadataCannotExhaustStackOrSnapshotBudget() {
        Map<String, Object> cycle = new HashMap<>();
        cycle.put("self", cycle);
        assertThrows(IllegalArgumentException.class, () -> new NostrRelayInfo("wss://test", cycle));
        List<Object> deep = new ArrayList<>();
        List<Object> next = deep;
        for (int i = 0; i < 1000; i++) {
            List<Object> child = new ArrayList<>();
            next.add(child);
            next = child;
        }
        assertThrows(IllegalArgumentException.class, () -> ImmutableSnapshot.snapshotValue(deep));
        assertThrows(IllegalArgumentException.class, () -> ImmutableSnapshot.snapshotValue(Collections.nCopies(100001, "x")));
        assertThrows(
            IllegalArgumentException.class,
            () -> ImmutableSnapshot.validateJsonBounds("[".repeat(1000) + "0" + "]".repeat(1000))
        );
        assertThrows(IllegalArgumentException.class, () -> ImmutableSnapshot.validateJsonBounds(" ".repeat(1048577)));
        ImmutableSnapshot.validateJsonBounds("{\"description\":\"" + "[".repeat(1000) + "\"}");
        List<String> shared = new ArrayList<>(List.of("original"));
        Map<String, Object> frozen = ImmutableSnapshot.snapshotMap(Map.of("a", shared, "b", shared));
        shared.clear();
        assertEquals(List.of("original"), frozen.get("a"));
    }
}
